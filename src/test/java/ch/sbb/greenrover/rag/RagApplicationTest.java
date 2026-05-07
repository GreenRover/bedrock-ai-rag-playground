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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"aws.bedrock.token=mock-token", "CONFLUENCE_TOKEN=mock-token"})
class RagApplicationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        EmbeddingModel embeddingModel() {
            return new AllMiniLmL6V2EmbeddingModel();
        }
    }

    @MockitoBean
    BedrockRuntimeClient bedrockRuntimeClient;

    @Autowired
    ContentRetriever contentRetriever;

    @Test
    void testContextLengthForConcentrator() {
        Query query = Query.from("Was ist ein concentrator");
        List<Content> contents = contentRetriever.retrieve(query);

        int totalRacLength = contents.stream().mapToInt(c -> c.textSegment().text().length()).sum();
        System.out.println("Retrieved RAG context length: " + totalRacLength);

        assertThat(totalRacLength).isGreaterThan(10000);
        assertThat(totalRacLength).isLessThanOrEqualTo(150000);
    }
}

