package ch.sbb.tms.ssp.chat.config.spring;

import ch.sbb.tms.ssp.chat.config.properties.RagProperties;
import ch.sbb.tms.ssp.chat.service.Assistant;
import ch.sbb.tms.ssp.chat.service.BedrockAmazonScoringModel;
import ch.sbb.tms.ssp.chat.service.LoggingReRankingContentAggregator;
import ch.sbb.tms.ssp.chat.service.PostgresHybridRetriever;
import ch.sbb.tms.ssp.chat.service.tools.DocumentRetrievalTools;
import ch.sbb.tms.ssp.chat.service.tools.SolaceBrokerTools;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.bedrock.BedrockTitanEmbeddingModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

@Slf4j
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
                // Configure the Apache HTTP client to reap connections that are idle (ai is thinking very long)
                .httpClientBuilder(ApacheHttpClient.builder()
                        // 1. Prevent EOF errors from AWS dropping idle connections
                        .connectionMaxIdleTime(Duration.ofSeconds(30))
                        // 2. Allow Bedrock plenty of time to "think" and generate long responses
                        .socketTimeout(Duration.ofMinutes(3))
                )
                .build();
    }

    @Bean
    ChatModel chatModel(
            BedrockRuntimeClient client,
            RagProperties ragProperties
    ) {
        var chatProps = ragProperties.getChat();

        Assert.notNull(chatProps.getModelId(), "Chat model ID must not be null");
        Assert.notNull(chatProps.getTemperature(), "Chat temperature must not be null");
        Assert.notNull(chatProps.getMaxOutputTokens(), "Chat max output tokens must not be null");

        return BedrockChatModel.builder()
                .client(client)
                .modelId(chatProps.getModelId())
                .defaultRequestParameters(BedrockChatRequestParameters.builder()
                        .temperature(chatProps.getTemperature())
                        .maxOutputTokens(chatProps.getMaxOutputTokens())
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
                .table(schema + "." + PostgresHybridRetriever.TABLE_NAME)
                .dimension(1024) // Titan v2 text
                .build();
    }

    @Bean
    ContentRetriever contentRetriever(PostgresHybridRetriever postgresHybridRetriever) {
        return postgresHybridRetriever;
    }

    @Bean
    ContentAggregator contentAggregator(
            BedrockAmazonScoringModel scoringModel,
            RagProperties ragProperties
    ) {
        var rerankProps = ragProperties.getRerank();

        Assert.notNull(rerankProps.getModelId(), "Rerank model ID must not be null");
        Assert.notNull(rerankProps.getMinScore(), "Rerank minimum score must not be null");
        Assert.notNull(rerankProps.getMaxResults(), "Rerank maximum results must not be null");

        return LoggingReRankingContentAggregator.builder()
                .scoringModel(scoringModel)
                .minScore(rerankProps.getMinScore())
                .maxResults(rerankProps.getMaxResults())
                .build();
    }

    @Bean
    ChatModel translationChatModel(
            BedrockRuntimeClient client,
            RagProperties ragProperties
    ) {
        var chatTranslationProps = ragProperties.getChat().getTranslation();

        Assert.notNull(chatTranslationProps.getModelId(), "Translation model ID must not be null");
        Assert.notNull(chatTranslationProps.getTemperature(), "Translation temperature must not be null");

        return BedrockChatModel.builder()
                .client(client)
                .modelId(chatTranslationProps.getModelId())
                .defaultRequestParameters(BedrockChatRequestParameters.builder()
                        .temperature(chatTranslationProps.getTemperature())
                        .build())
                .build();
    }

    @Bean
    QueryTransformer translationQueryTransformer(ChatModel translationChatModel) {
        return query -> {
            String prompt = """
                    You are an expert technical translator and search query optimizer for an enterprise messaging (Solace), prometheus monitoring and Java knowledge base.
                    Your task is to prepare the user's query for a hybrid vector and full-text database search.

                    <instructions>
                    1. Translate the query into English if it is not already in English.
                    2. Expand common technical and messaging abbreviations/acronyms by including BOTH the abbreviation and the full term to maximize search hits (e.g., change 'ttl' to 'ttl time to live', 'ha' to 'ha high availability', 'dr' to 'dr disaster recovery', 'msg' to 'msg message').
                    3. Add highly relevant synonyms if the user uses informal terms.
                    4. Do not answer the question; only rewrite the query.
                    5. Output ONLY the optimized English query, without any conversational filler, quotes, prefixes, or markdown.
                    </instructions>
                    """;

            String translatedText = translationChatModel.chat(
                    SystemMessage.from(prompt),
                    UserMessage.from(query.text())
            ).aiMessage().text();

            log.info("Query translation: original={}, translated={}", query.text(), translatedText);

            return Collections.singletonList(Query.from(translatedText));
        };
    }

    @Bean
    Assistant assistant(
            ChatModel chatModel,
            ContentRetriever contentRetriever,
            QueryTransformer translationQueryTransformer,
            ContentAggregator contentAggregator,
            SolaceBrokerTools solaceBrokerTools,
            DocumentRetrievalTools documentRetrievalTools
    ) {
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
                .tools(solaceBrokerTools, documentRetrievalTools)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }
}
