package ch.sbb.greenrover.rag.service;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Slf4j
@Service
public class DocumentTranslationService {

    private final ChatModel translatorModel;
    private final LanguageDetector languageDetector;

    public DocumentTranslationService(BedrockRuntimeClient bedrockRuntimeClient) {
        this.languageDetector = LanguageDetectorBuilder.fromLanguages(Language.ENGLISH, Language.GERMAN, Language.FRENCH, Language.ITALIAN).build();
        this.translatorModel = BedrockChatModel.builder()
                .client(bedrockRuntimeClient)
                .modelId("eu.amazon.nova-pro-v1:0")
                .defaultRequestParameters(BedrockChatRequestParameters.builder()
                        .temperature(0.1)
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

            // Step 2: Translate
            log.info("Translating {} to English... (detected language: {})", fileName, detectedLanguage);
            SystemMessage transSys = SystemMessage.from("You are an expert technical translator. Translate the provided Markdown document into English. \n" +
                    "CRITICAL INSTRUCTIONS:\n" +
                    "- Preserve all Markdown formatting, YAML frontmatter, HTML tags, links, and code blocks exactly as they are.\n" +
                    "- Only translate the natural language text.\n" +
                    "- Output ONLY the translated Markdown. Do not add intro/outro text.");
            UserMessage transUser = UserMessage.from(originalMarkdown);
            return translatorModel.chat(transSys, transUser).aiMessage().text();

        } catch (Exception e) {
            log.error("Failed to translate file: {}, returning original content", fileName, e);
            return originalMarkdown;
        }
    }
}