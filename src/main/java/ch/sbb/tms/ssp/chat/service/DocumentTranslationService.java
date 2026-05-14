package ch.sbb.tms.ssp.chat.service;

import ch.sbb.tms.ssp.chat.config.properties.RagProperties;
import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static ch.sbb.tms.ssp.chat.service.ConfluenceToMarkdownService.ATTACHMENT_PREFIX;
import static ch.sbb.tms.ssp.chat.service.ConfluenceToMarkdownService.ATTACHMENT_SUFFIX;

@Slf4j
@Service
public class DocumentTranslationService {

    // Define a safe max characters limit per chunk (~1000 tokens, well below standard output limits)
    private static final int MAX_CHUNK_SIZE = 4000;
    public static final String ENGLISH_CACHE_FILE_SUFFIX = ".en-cache";

    private final ChatModel translatorModel;
    private final LanguageDetector languageDetector;
    private final RagProperties ragProperties;

    private Path EXPORT_DIR;
    private Path ASSETS_DIR;

    @PostConstruct
    public void init() {
        EXPORT_DIR = Path.of(ragProperties.getData().getExportDir());
        ASSETS_DIR = EXPORT_DIR.resolve("assets");
    }

    public DocumentTranslationService(BedrockRuntimeClient bedrockRuntimeClient, RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.languageDetector = LanguageDetectorBuilder.fromLanguages(Language.ENGLISH, Language.GERMAN, Language.FRENCH, Language.ITALIAN).build();

        var translationProps = ragProperties.getData().getTranslation();

        Assert.hasText(translationProps.getModelId(), "Translation model ID must not be null or empty");
        Assert.notNull(translationProps.getTemperature(), "Translation temperature must not be null");

        this.translatorModel = BedrockChatModel.builder()
                .client(bedrockRuntimeClient)
                .modelId(translationProps.getModelId())
                .defaultRequestParameters(BedrockChatRequestParameters.builder()
                        .temperature(translationProps.getTemperature())
                        .build())
                .build();
    }

    public String translateMarkdownToEnglishAndInjectAttachmentDescription(Path markdownPath) throws IOException {
        String content = Files.readString(markdownPath, StandardCharsets.UTF_8);
        content = replaceAttachmentPlaceholdersWithAttachmentDescription(markdownPath, content);

        Path cachePath = Path.of(markdownPath + ENGLISH_CACHE_FILE_SUFFIX);
        if (Files.exists(cachePath) && Files.getLastModifiedTime(cachePath).compareTo(Files.getLastModifiedTime(markdownPath)) > 0) {
            log.debug("Cache hit for {}", markdownPath.getFileName().toString());
            return Files.readString(cachePath, StandardCharsets.UTF_8);
        } else {
            content = ensureEnglish(markdownPath.getFileName().toString(), content);
            Files.writeString(cachePath, content, StandardCharsets.UTF_8);
            log.debug("Translated to English {}", markdownPath.getFileName().toString());
            return content;
        }
    }

    private String replaceAttachmentPlaceholdersWithAttachmentDescription(Path txtPath, String content) throws IOException {
        String fileName = txtPath.getFileName().toString();

        Path pageAssetsDir = null;
        try (Stream<Path> dirs = Files.list(ASSETS_DIR)) {
            Optional<Path> foundDir = dirs.filter(Files::isDirectory)
                    .filter(d -> fileName.startsWith(d.getFileName().toString() + "_"))
                    .max(Comparator.comparingInt(d -> d.getFileName().toString().length()));
            if (foundDir.isPresent()) {
                pageAssetsDir = foundDir.get();
            }
        }

        if (pageAssetsDir == null || !Files.exists(pageAssetsDir)) {
            return content;
        }

        try (Stream<Path> assetPaths = Files.list(pageAssetsDir)) {
            for (Path assetTxtPath : assetPaths.filter(p -> p.toString().endsWith(".md")).toList()) {
                String assetFileName = assetTxtPath.getFileName().toString().replace(".md", "");
                String assetContent = Files.readString(assetTxtPath, StandardCharsets.UTF_8);
                content = content.replace(ATTACHMENT_PREFIX + assetFileName + ATTACHMENT_SUFFIX,
                        "\n--- Asset Translation: " + assetFileName + " ---\n" + assetContent + "\n");
            }
        }
        return content;
    }

    private String ensureEnglish(String fileName, String originalMarkdown) {
        try {
            // Step 1: Detect Language locally
            String snippet = originalMarkdown.length() > 1000 ? originalMarkdown.substring(0, 1000) : originalMarkdown;
            Language detectedLanguage = languageDetector.detectLanguageOf(snippet);

            if (detectedLanguage == Language.ENGLISH) {
                log.info("File {} is already English, skipping translation", fileName);
                return originalMarkdown;
            }

            // Step 2: Translate in chunks to prevent hitting output token limits
            log.info("Translating {} to English... (detected language: {})", fileName, detectedLanguage);
            SystemMessage transSys = SystemMessage.from("""
                    You are an expert technical translator. Translate the provided Markdown document into English.\s
                    CRITICAL INSTRUCTIONS:
                    - Preserve all Markdown formatting, YAML frontmatter, HTML tags, links, and code blocks exactly as they are.
                    - Only translate the natural language text.
                    - Output ONLY the translated Markdown. Do not add intro/outro text.""");

            List<String> chunks = splitMarkdown(originalMarkdown, MAX_CHUNK_SIZE);
            StringBuilder translatedContent = new StringBuilder();

            for (int i = 0; i < chunks.size(); i++) {
                log.info("Translating chunk {}/{} for file {}", i + 1, chunks.size(), fileName);
                UserMessage transUser = UserMessage.from(chunks.get(i));
                String translatedChunk = translatorModel.chat(transSys, transUser).aiMessage().text();
                translatedContent.append(translatedChunk);
            }

            return translatedContent.toString();

        } catch (Exception e) {
            log.error("Failed to translate file: {}, returning original content", fileName, e);
            return originalMarkdown;
        }
    }

    /**
     * Splits the markdown document into chunks, preferring double newlines (paragraphs)
     * as split points to maintain document structure and avoid breaking mid-sentence.
     */
    @SuppressWarnings("SameParameterValue")
    private List<String> splitMarkdown(String markdown, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return chunks;
        }

        // Split by double newline while preserving the delimiters using positive lookbehind
        String[] paragraphs = markdown.split("(?<=\\n\\n)");
        StringBuilder currentChunk = new StringBuilder();

        for (String p : paragraphs) {
            // If adding the next paragraph exceeds the limit, commit the current chunk
            if (currentChunk.length() + p.length() > maxChunkSize && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }
            currentChunk.append(p);
        }

        // Add any remaining text
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    public void translateAllMarkdowns() throws IOException {
        List<Path> targetFiles;
        try (Stream<Path> paths = Files.walk(EXPORT_DIR)) {
            targetFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getParent().equals(EXPORT_DIR))
                    .filter(p -> p.toString().endsWith(".md"))
                    .toList();
        }

        int total = targetFiles.size();
        AtomicInteger processed = new AtomicInteger(0);
        AtomicLong lastPrintTime = new AtomicLong(System.currentTimeMillis());

        targetFiles.stream()
                .parallel()
                .forEach(markdownPath -> {
                    try {
                        translateMarkdownToEnglishAndInjectAttachmentDescription(markdownPath);
                    } catch (IOException e) {
                        log.error("Error reading file: {}", markdownPath, e);
                    }

                    synchronized (lastPrintTime) {
                        int current = processed.incrementAndGet();
                        long now = System.currentTimeMillis();
                        long lastTime = lastPrintTime.get();
                        if (now - lastTime > 60000 && lastPrintTime.compareAndSet(lastTime, now)) {
                            log.info("Document translation progress: {}/{} completed", current, total);
                        }
                    }
                });

        log.info("Done! All files necessary was translated to English.");
    }

    /**
     * Delete cache file to force translation service to re-run
     */
    public static void deleteCacheFile(Path filePath) {
        try {
            Path cacheFilePath = Path.of(filePath + ENGLISH_CACHE_FILE_SUFFIX);
            Files.deleteIfExists(cacheFilePath);
        } catch (IOException e) {
            log.warn("Failed to delete cache file for {}: {}", filePath.getFileName().toString(), e.getMessage());
        }
    }
}