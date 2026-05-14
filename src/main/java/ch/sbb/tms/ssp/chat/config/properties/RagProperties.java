package ch.sbb.tms.ssp.chat.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private DataProperties data;
    private RerankProperties rerank;
    private ChatProperties chat;

    @Data
    public static class DataProperties {
        private String exportDir;
        private TranslationProperties translation;
    }

    @Data
    public static class TranslationProperties {
        private String modelId;
        private Double temperature;
    }

    @Data
    public static class RerankProperties {
        private String modelId;
        private Double minScore;
        private Integer maxResults;
    }

    @Data
    public static class ChatProperties {
        private String modelId;
        private Double temperature;
        private Integer maxOutputTokens;
        private TranslationProperties translation;
    }
}
