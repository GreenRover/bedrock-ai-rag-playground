package ch.sbb.greenrover.rag.service;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class BedrockMediaTranslationService {

    private final ChatModel chatModel;

    @Autowired
    public BedrockMediaTranslationService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void extractTextWithBedrock(Path targetFile, String title, Path pageAssetsDir) {
        try {
            String mimeType = Files.probeContentType(targetFile);
            if (mimeType == null) {
                log.debug("Skipping file {} with unknown mime type", title);
                return;
            }
            if (!mimeType.startsWith("image/") && !mimeType.equals("application/pdf") && !mimeType.startsWith("video/")) {
                log.debug("Skipping unsupported mime type {} for file {}", mimeType, title);
                return;
            }

            long fileSize = Files.size(targetFile);
            if (fileSize > 25 * 1024 * 1024) {
                log.warn("Skipping file {} because it is larger than 25MB (size: {} bytes). " +
                         "TODO: Implement S3 upload for processing large files with Bedrock.", title, fileSize);
                return;
            }

            UserMessage userMessage = null;
            byte[] fileBytes = Files.readAllBytes(targetFile);
            String base64Data = java.util.Base64.getEncoder().encodeToString(fileBytes);

            if (mimeType.startsWith("image/")) {
                String baseName = title.contains(".") ? title.substring(0, title.lastIndexOf('.')) : title;
                Path relatedXmlFile = pageAssetsDir.resolve(baseName);
                String xmlHint = "";
                
                if (Files.exists(relatedXmlFile) && Files.isRegularFile(relatedXmlFile) && !relatedXmlFile.equals(targetFile)) {
                    String xmlContent = Files.readString(relatedXmlFile, StandardCharsets.UTF_8);
                    xmlHint = "\n\nAdditionally, here is the raw draw.io XML content related to this diagram to help you understand its structure:\n" + xmlContent;
                }

                userMessage = UserMessage.from(
                        TextContent.from("Please extract all text from this image. If it's a diagram, describe it." + xmlHint),
                        ImageContent.from(base64Data, mimeType)
                );
            } else if (mimeType.equals("application/pdf")) {
                userMessage = UserMessage.from(
                        TextContent.from("Please extract all text from this PDF document. Summarize any important tables or figures."),
                        new PdfFileContent(base64Data, mimeType)
                );
            } else if (mimeType.startsWith("video/")) {
                userMessage = UserMessage.from(
                        TextContent.from("Please extract all text and spoken words from this video. Describe what is happening."),
                        new VideoContent(base64Data, mimeType)
                );
            }

            if (userMessage != null) {
                log.info("Processing asset with Bedrock: {}", title);
                ChatResponse response = chatModel.chat(ChatRequest.builder().messages(userMessage).build());
                String extractedText = response.aiMessage().text();

                Path textFile = pageAssetsDir.resolve(title + ".txt");
                Files.writeString(textFile, extractedText, StandardCharsets.UTF_8);
                log.info("Extracted text saved to {}", textFile);
            }
        } catch (Exception e) {
            log.error("Failed to process attachment with Bedrock: {}", title, e);
        }
    }
}