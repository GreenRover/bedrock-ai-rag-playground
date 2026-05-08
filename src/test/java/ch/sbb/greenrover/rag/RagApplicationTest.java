package ch.sbb.greenrover.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.List;

import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"aws.bedrock.token=mock-token", "CONFLUENCE_TOKEN=mock-token", "spring.main.allow-bean-definition-overriding=true"})
class RagApplicationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        EmbeddingModel embeddingModel() {
            return new AllMiniLmL6V2EmbeddingModel();
        }

        @Bean
        @Primary
        EmbeddingStore<TextSegment> embeddingStore() {
            return new InMemoryEmbeddingStore<>();
        }
    }

    @MockitoBean
    BedrockRuntimeClient bedrockRuntimeClient;

    @Autowired
    ContentRetriever contentRetriever;

    @Test
    void contextLoads() {
        assertThat(contentRetriever).isNotNull();
    }
}

