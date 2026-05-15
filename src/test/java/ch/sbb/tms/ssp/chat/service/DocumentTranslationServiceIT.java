package ch.sbb.tms.ssp.chat.service;

import ch.sbb.tms.ssp.chat.config.properties.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = { DocumentTranslationService.class, DocumentTranslationServiceIT.TestConfig.class, RagProperties.class })
@EnableConfigurationProperties(RagProperties.class)
@ActiveProfiles("test")
class DocumentTranslationServiceIT {

    @TestConfiguration
    static class TestConfig {
        @Bean
        BedrockRuntimeClient bedrockRuntimeClient(@Value("${aws.bedrock.token:}") String secret) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalArgumentException("aws.bedrock.token must be provided in application.yml or environment");
            }
            return BedrockRuntimeClient.builder()
                    .region(Region.EU_CENTRAL_1)
                    .credentialsProvider(AnonymousCredentialsProvider.create())
                    .overrideConfiguration(c -> c.putHeader("Authorization", "Bearer " + secret))
                    .httpClientBuilder(ApacheHttpClient.builder()
                            .connectionMaxIdleTime(Duration.ofSeconds(30))
                            .socketTimeout(Duration.ofMinutes(3))
                    )
                    .build();
        }
    }

    @Autowired
    private DocumentTranslationService documentTranslationService;

    private static final Path INPUT_FILE = Paths.get("src/test/resources/data_export/1264812703_11._API_Konfiguration_(conf.json).md");
    private static final Path CACHE_FILE = Paths.get(INPUT_FILE + DocumentTranslationService.ENGLISH_CACHE_FILE_SUFFIX);

    @BeforeEach
    void setUp() throws IOException {
        DocumentTranslationService.deleteCacheFile(INPUT_FILE);
    }

    @Test
    void testTranslateMarkdownToEnglishAndInjectAttachmentDescription() throws IOException {
        // Prepare
        assertThat(Files.exists(CACHE_FILE)).isFalse();

        String originalContent = Files.readString(INPUT_FILE);

        // Execute
        String translatedContent = documentTranslationService.translateMarkdownToEnglishAndInjectAttachmentDescription(INPUT_FILE);

        // Assert
        assertThat(translatedContent).isNotNull();

        // extract 10 german words from source and assert that are not longer contained in output
        List<String> germanWords = List.of(
                "Initialerstellung", "Validierung", "anleitung",
                "schnittstellenspezifischen", "entwicklungsumgebungen",
                "veröffentlicht", "berechtigt", "auswirkungen",
                "zurückgewiesen", "darstellen"
        );
        for (String word : germanWords) {
            assertThat(translatedContent.toLowerCase()).doesNotContain(word.toLowerCase());
        }

        // that the english words like Property, Limits, Application, Plansare in the output
        // (Note: in the original they might be title cased or upper cased)
        List<String> englishWords = List.of("Property", "Limits", "Application", "Plans");
        for (String word : englishWords) {
            assertThat(translatedContent).containsIgnoringCase(word);
        }

        // that the output is not significant (70% or input) shorter than the input
        double ratio = (double) translatedContent.length() / originalContent.length();
        assertThat(ratio).isGreaterThan(0.70);
    }
}
