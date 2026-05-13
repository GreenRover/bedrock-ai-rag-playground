package ch.sbb.tms.ssp.chat.service;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DocumentTranslationService {

    // Define a safe max characters limit per chunk (~1000 tokens, well below standard output limits)
    private static final int MAX_CHUNK_SIZE = 4000;

    private final ChatModel translatorModel;
    private final LanguageDetector languageDetector;

    @Value("${rag.data.transation.model-id}")
    String modelId;

    @Value("${rag.data.transation.temperature}")
    Double temperature;

    public DocumentTranslationService(BedrockRuntimeClient bedrockRuntimeClient) {
        this.languageDetector = LanguageDetectorBuilder.fromLanguages(Language.ENGLISH, Language.GERMAN, Language.FRENCH, Language.ITALIAN).build();
        this.translatorModel = BedrockChatModel.builder()
                .client(bedrockRuntimeClient)
                .modelId(modelId)
                .defaultRequestParameters(BedrockChatRequestParameters.builder()
                        .temperature(temperature)
                        .build())
                .build();
    }

    public String ensureEnglish(String fileName, String originalMarkdown) {
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
            SystemMessage transSys = SystemMessage.from("You are an expert technical translator. Translate the provided Markdown document into English. \n" +
                    "CRITICAL INSTRUCTIONS:\n" +
                    "- Preserve all Markdown formatting, YAML frontmatter, HTML tags, links, and code blocks exactly as they are.\n" +
                    "- Only translate the natural language text.\n" +
                    "- Output ONLY the translated Markdown. Do not add intro/outro text.");

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
}