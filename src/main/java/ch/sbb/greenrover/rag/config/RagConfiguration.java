package ch.sbb.greenrover.rag.config;

import ch.sbb.greenrover.rag.service.Assistant;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.bedrock.BedrockTitanEmbeddingModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;

@Configuration
public class RagConfiguration {

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(@Value("${aws.bedrock.token:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("aws.bedrock.token must be provided in application.yml or environment");
        }
        return BedrockRuntimeClient.builder()
                .region(Region.EU_CENTRAL_1)
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .overrideConfiguration(c -> c.putHeader("Authorization", "Bearer " + secret))
                .build();
    }

    @Bean
    ChatModel chatModel(
            BedrockRuntimeClient client,
            @Value("${rag.chat.model-id}") String modelId,
            @Value("${rag.chat.temperature}") Double temperature,
            @Value("${rag.chat.max-output-tokens}") Integer maxOutputTokens
    ) {
        return BedrockChatModel.builder()
                .client(client)
                .modelId(modelId)
                .defaultRequestParameters(BedrockChatRequestParameters.builder()
                        .temperature(temperature)
                        .maxOutputTokens(maxOutputTokens)
                        .build())
                .build();
    }

    @Bean
    EmbeddingModel embeddingModel(BedrockRuntimeClient client) {
        return BedrockTitanEmbeddingModel.builder()
                .client(client)
                .model("amazon.titan-embed-text-v2:0")
                .build();
    }

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password}") String password
    ) {
        URI uri = URI.create(url.substring(5)); // Remove "jdbc:"
        String host = uri.getHost();
        int port = uri.getPort() != -1 ? uri.getPort() : 5432;
        String database = uri.getPath().substring(1);

        String schema = "public";
        if (uri.getQuery() != null) {
            for (String param : uri.getQuery().split("&")) {
                if (param.startsWith("currentSchema=")) {
                    schema = param.substring("currentSchema=".length());
                    break;
                }
            }
        }

        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(user)
                .password(password)
                .table(schema + ".embeddings")
                .dimension(1024) // Titan v2 text
                .build();
    }

    @Bean
    ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        return dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(50)
                .build();
    }

    @Bean
    ContentAggregator contentAggregator(ch.sbb.greenrover.rag.service.BedrockCohereScoringModel scoringModel) {
        return ReRankingContentAggregator.builder()
                .scoringModel(scoringModel)
                .minScore(0.5)
                .build();
    }

    @Bean
    ChatModel translationChatModel(BedrockRuntimeClient client) {
        return BedrockChatModel.builder()
                .client(client)
                .modelId("eu.amazon.nova-lite-v1:0")
                .defaultRequestParameters(BedrockChatRequestParameters.builder()
                        .temperature(0.0)
                        .build())
                .build();
    }

    @Bean
    QueryTransformer translationQueryTransformer(ChatModel translationChatModel) {
        return query -> {
            String prompt = "You are an expert translator. Translate the following user query into English to optimize it for a vector database search. Only return the English translation, without any conversational filler, quotes, or markdown. If the text is already in English, return it exactly as is.";
            String translatedText = translationChatModel.chat(
                    SystemMessage.from(prompt),
                    UserMessage.from(query.text())
            ).aiMessage().text();
            System.out.println("Original Query: " + query.text());
            System.out.println("Translated Query: " + translatedText);
            return Collections.singletonList(Query.from(translatedText));
        };
    }

    @Bean
    Assistant assistant(ChatModel chatModel, ContentRetriever contentRetriever, QueryTransformer translationQueryTransformer, ContentAggregator contentAggregator) {
        DefaultContentInjector contentInjector = DefaultContentInjector.builder()
                .metadataKeysToInclude(Arrays.asList("url", "title", "title_path", "outbound_links", "parent_context", "last_updated"))
                .build();

        DefaultRetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(translationQueryTransformer)
                .contentRetriever(contentRetriever)
                .contentAggregator(contentAggregator)
                .contentInjector(contentInjector)
                .build();

        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .retrievalAugmentor(augmentor)
                .build();
    }
}
