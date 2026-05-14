package ch.sbb.tms.ssp.chat.service;

import ch.sbb.tms.ssp.chat.config.properties.RagProperties;
import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import dev.langchain4j.data.message.*;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BedrockMediaTranslationService {

    private final ChatModel chatModel;
    private final ContentInfoUtil mimeUtil = new ContentInfoUtil();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final RagProperties ragProperties;

    public void translateAllAssets() throws IOException {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Asset translation is already running, skipping...");
            return;
        }

        try {
            Path exportDir = Path.of(ragProperties.getData().getExportDir());

            Path assetsDir = exportDir.resolve("assets");
            if (!Files.exists(assetsDir)) return;

            log.info("Starting global asset translation in: {}...", assetsDir);

            List<Path> targetFiles;
            try (Stream<Path> paths = Files.walk(assetsDir)) {
                targetFiles = paths.filter(Files::isRegularFile)
                        .filter(p -> !p.toString().endsWith(".md")) // Skip already generated MD files
                        .toList();
            }

            int total = targetFiles.size();
            AtomicInteger processed = new AtomicInteger(0);
            AtomicLong lastPrintTime = new AtomicLong(System.currentTimeMillis());

            try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
                List<CompletableFuture<Void>> futures = targetFiles.stream()
                        .map(assetPath -> runAsync(() -> {
                            Path textFile = assetPath.resolveSibling(assetPath.getFileName() + ".md");
                            if (!Files.exists(textFile)) {
                                extractTextWithBedrock(assetPath, assetPath.getFileName().toString(), assetPath.getParent());
                            } else {
                                log.debug("Attachment already translated, skipping: {}", assetPath.getFileName());
                            }

                            synchronized (lastPrintTime) {
                                int current = processed.incrementAndGet();
                                long now = System.currentTimeMillis();
                                long lastTime = lastPrintTime.get();
                                if (now - lastTime > 60000 && lastPrintTime.compareAndSet(lastTime, now)) {
                                    log.info("Asset translation progress: {}/{} completed", current, total);
                                }
                            }
                        }, executor))
                        .toList();
                allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            log.info("Global asset translation completed.");
        } finally {
            isRunning.set(false);
        }
    }

    private void extractTextWithBedrock(Path targetFile, String title, Path pageAssetsDir) {
        try {
            ContentInfo info = mimeUtil.findMatch(targetFile.toFile());
            String mimeType;

            if (info != null) {
                mimeType = info.getMimeType();
            } else {
                // Fallback to lookup by file ending if simplemagic fails to detect
                mimeType = Files.probeContentType(targetFile);
            }

            if ("text/plain".equals(mimeType)) {
                log.info("File {} detected as text/plain, retaining content directly", title);
                String content = Files.readString(targetFile, StandardCharsets.UTF_8);
                Path textFile = pageAssetsDir.resolve(title + ".md");
                Files.writeString(textFile, content, StandardCharsets.UTF_8);
                return;
            }

            if (isUnsupported(mimeType, title)) {
                return;
            }

            long fileSize = Files.size(targetFile);
            if (isTooLarge(fileSize, title)) {
                return;
            }

            UserMessage userMessage = createUserMessage(targetFile, title, pageAssetsDir, mimeType);

            if (userMessage != null) {
                processWithModel(userMessage, title, pageAssetsDir);
            }
        } catch (Exception e) {
            log.error("Failed to process attachment with Bedrock: {}", title, e);
        }
    }

    private boolean isUnsupported(String mimeType, String title) {
        if (mimeType == null) {
            log.debug("Skipping file {} with unknown mime type", title);
            return true;
        }
        // Only pdf and images are supported: dev.langchain4j.model.bedrock.AbstractBedrockChatModel.convertContent
        if (mimeType.startsWith("image/")) {
            if (!mimeType.equals("image/png") && !mimeType.equals("image/jpeg") &&
                    !mimeType.equals("image/gif") && !mimeType.equals("image/webp")) {
                log.debug("Skipping unsupported image format {} for file {}", mimeType, title);
                return true;
            }
        } else if (!mimeType.equals("application/pdf")) {
            log.debug("Skipping unsupported mime type {} for file {}", mimeType, title);
            return true;
        }
        return false;
    }

    private boolean isTooLarge(long fileSize, String title) {
        if (fileSize > 25 * 1024 * 1024) {
            log.warn("Skipping file {} because it is larger than 25MB (size: {} bytes). " +
                    "TODO: Implement S3 upload for processing large files with Bedrock.", title, fileSize);
            return true;
        }
        return false;
    }

    private UserMessage createUserMessage(Path targetFile, String title, Path pageAssetsDir, String mimeType) throws Exception {
        byte[] fileBytes = Files.readAllBytes(targetFile);
        String base64Data = Base64.getEncoder().encodeToString(fileBytes);

        if (mimeType.startsWith("image/")) {
            return createImageMessage(base64Data, mimeType, title, pageAssetsDir, targetFile);
        } else if (mimeType.equals("application/pdf")) {
            return createPdfMessage(base64Data, mimeType);
        } else if (mimeType.startsWith("video/")) {
            return createVideoMessage(base64Data, mimeType);
        }
        return null;
    }

    private UserMessage createImageMessage(String base64Data, String mimeType, String title, Path pageAssetsDir, Path targetFile) throws Exception {
        String baseName = title.contains(".") ? title.substring(0, title.lastIndexOf('.')) : title;
        Path relatedXmlFile = pageAssetsDir.resolve(baseName);
        String xmlHint = "";

        if (Files.exists(relatedXmlFile) && Files.isRegularFile(relatedXmlFile) && !relatedXmlFile.equals(targetFile)) {
            String xmlContent = Files.readString(relatedXmlFile, StandardCharsets.UTF_8);
            xmlHint = "\n\n<additional_context>\nHere is the raw draw.io XML content related to this diagram to help you understand its structure:\n" + xmlContent + "\n</additional_context>";
        }

        String prompt = """
                You are an expert technical data extractor preparing content for a RAG knowledge base.
                Analyze this image and output the results in strict Markdown format.

                <instructions>
                1. Provide a concise 1-2 sentence Summary of what the image represents.
                2. Transcribe all meaningful visible text.
                3. If it is a diagram, explicitly describe the components and their connections or data flows (e.g., 'Node A connects to Node B').
                4. If it is a UI screenshot, describe what is happening in the UI.
                5. Do NOT include any conversational filler (e.g., "Here is the text"). Output only the Markdown.
                </instructions>
                """ + xmlHint;

        return UserMessage.from(TextContent.from(prompt), ImageContent.from(base64Data, mimeType));
    }

    private UserMessage createPdfMessage(String base64Data, String mimeType) {
        String prompt = """
                You are an expert technical data extractor preparing content for a RAG knowledge base.
                Analyze this PDF document and output the results in strict Markdown format.

                <instructions>
                1. Provide a concise Summary of the document's purpose and content.
                2. Transcribe all meaningful text, maintaining the logical structure.
                3. Summarize any important tables or figures, explaining their relevance and key data points.
                4. Identify any relationships or flow between different sections or concepts described.
                5. Do NOT include any conversational filler. Output only the Markdown.
                </instructions>
                """;
        return UserMessage.from(TextContent.from(prompt), new PdfFileContent(base64Data, mimeType));
    }

    private UserMessage createVideoMessage(String base64Data, String mimeType) {
        String prompt = """
                You are an expert technical data extractor preparing video content for a RAG knowledge base.
                Analyze this video and output the results in strict Markdown format.

                <instructions>
                1. Provide a concise 1-6 sentence Summary of the video's overall topic.
                2. Transcription: Extract and format all spoken words and narration.
                3. Visuals & On-Screen Text: Describe important actions, diagrams, UI flows, or text shown on the screen.
                4. Do NOT include any conversational filler (e.g., "Here is the video description"). Output ONLY the Markdown.
                </instructions>
                """;
        return UserMessage.from(TextContent.from(prompt), new VideoContent(base64Data, mimeType));
    }

    private void processWithModel(UserMessage userMessage, String title, Path pageAssetsDir) throws Exception {
        log.info("Processing asset with Bedrock: {}", title);
        Path imageFile = pageAssetsDir.resolve(title);
        Path textFile = pageAssetsDir.resolve(title + ".md");
        try {
            ChatResponse response = chatModel.chat(ChatRequest.builder().messages(userMessage).build());
            String extractedText = response.aiMessage().text();

            Files.writeString(textFile, extractedText, StandardCharsets.UTF_8);
            log.info("Extracted text saved to {}", textFile);
        } catch (InvalidRequestException | ValidationException e) {
            if (e.getMessage().contains("tokens exceeds maximum length")) {
                log.warn("Bedrock response exceeded token limit for file {} ({} bytes). Error: {}", imageFile, Files.size(imageFile), e.getMessage());
                Files.writeString(textFile, "", StandardCharsets.UTF_8);
                return;
            }

            if (e instanceof InvalidRequestException) {
                log.warn("Bedrock response for file {} ({} bytes). Error: {}", imageFile, Files.size(imageFile), e.getMessage());
                Files.writeString(textFile, "", StandardCharsets.UTF_8);
                return;
            }

            log.error("Error processing asset with Bedrock: {}", textFile, e);
            throw e;
        }
    }
}