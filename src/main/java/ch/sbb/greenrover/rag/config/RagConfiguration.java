package ch.sbb.greenrover.rag.config;

import ch.sbb.greenrover.rag.service.Assistant;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.Arrays;
import java.util.List;

@Configuration
public class RagConfiguration {

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(@Value("${AWS_BEARER_TOKEN_BEDROCK:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("AWS_BEARER_TOKEN_BEDROCK must be provided in the environment");
        }
        return BedrockRuntimeClient.builder()
                .region(Region.EU_CENTRAL_1)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider.create())
                .overrideConfiguration(c -> c.putHeader("Authorization", "Bearer " + secret))
                .build();
    }

    @Bean
    ChatModel chatModel(BedrockRuntimeClient client) {
        return BedrockChatModel.builder()
                .client(client)
                .modelId("eu.amazon.nova-pro-v1:0")
                .defaultRequestParameters(BedrockChatRequestParameters.builder()
                        .temperature(0.1)
                        .maxOutputTokens(1000)
                        .build())
                .build();
    }

    @Bean
    EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        ContentRetriever delegate = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(100)
                .minScore(0.3)
                .build();

        return query -> {
            System.out.println("[DEBUG] User Query Token Length (chars): " + query.text().length());
            List<Content> contents = delegate.retrieve(query);
            
            int currentLength = 0;
            List<Content> limitedContents = new java.util.ArrayList<>();
            for (Content c : contents) {
                int len = c.textSegment().text().length();
                if (currentLength + len <= 150000) {
                    limitedContents.add(c);
                    currentLength += len;
                } else {
                    break;
                }
            }
            
            System.out.println("[DEBUG] RAG Injected Context Token Length (chars): " + currentLength);
            return limitedContents;
        };
    }

    @Bean
    Assistant assistant(ChatModel chatModel, ContentRetriever contentRetriever) {
        DefaultContentInjector contentInjector = DefaultContentInjector.builder()
                .metadataKeysToInclude(Arrays.asList("url", "title"))
                .build();

        DefaultRetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .contentRetriever(contentRetriever)
                .contentInjector(contentInjector)
                .build();

        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .retrievalAugmentor(augmentor)
                .build();
    }
}
