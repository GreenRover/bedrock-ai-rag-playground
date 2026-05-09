package ch.sbb.tms.ssp.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.model.output.Response;

@Slf4j
@Component
@RequiredArgsConstructor
public class BedrockAmazonScoringModel implements ScoringModel {

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("query", query);

            ArrayNode documents = payload.putArray("documents");
            for (TextSegment segment : segments) {
                documents.add(segment.text());
            }

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId("amazon.rerank-v1:0")
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            InvokeModelResponse response = bedrockRuntimeClient.invokeModel(request);
            JsonNode responseBody = objectMapper.readTree(response.body().asString(StandardCharsets.UTF_8));

            List<Double> scores = new ArrayList<>(segments.size());
            // Initialize list with zeros
            for (int i = 0; i < segments.size(); i++) {
                scores.add(0.0);
            }

            // Cohere returns an array of "results" containing "index" and "relevance_score"
            JsonNode results = responseBody.get("results");
            if (results != null && results.isArray()) {
                for (JsonNode result : results) {
                    int index = result.get("index").asInt();
                    double score = result.get("relevance_score").asDouble(0.0); // Adjust field name if Amazon changes their standard schema
                    scores.set(index, score);
                }
            }

            log.debug("Scoring segments with query: {}, scores: {}", query, scores);

            return Response.from(scores);

        } catch (Exception e) {
            log.error("Failed to score documents via Amazon Rerank", e);
            throw new RuntimeException("Reranking failed", e);
        }
    }
}