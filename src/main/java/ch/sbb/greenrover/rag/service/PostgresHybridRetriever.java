package ch.sbb.greenrover.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * A custom implementation of {@link ContentRetriever} that performs a hybrid search in PostgreSQL.
 *
 * <p><b>Why it exists:</b> The default retrievers typically only support semantic vector search.
 * This class exists to combine semantic vector search (cosine distance) with keyword-based
 * full-text search (ts_rank_cd) natively in PostgreSQL using a single SQL query.</p>
 *
 * <p><b>What it provides / improves:</b></p>
 * <ul>
 *   <li><b>Better Retrieval Accuracy:</b> Combines the contextual understanding of vector embeddings
 *       with the exact keyword matching of full-text search.</li>
 *   <li><b>Performance:</b> Executes both search strategies and scores them within the database,
 *       reducing data transfer and application-side processing.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostgresHybridRetriever implements ContentRetriever {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<Content> retrieve(Query query) {
        String queryText = query.text();
        dev.langchain4j.data.embedding.Embedding queryEmbedding = embeddingModel.embed(queryText).content();

        String vectorStr = java.util.Arrays.toString(queryEmbedding.vector());

        String sql = "SELECT text, metadata, " +
                "((1.0 / (1.0 + (embedding <-> ?::vector))) + " +
                "ts_rank_cd(to_tsvector('english', text), plainto_tsquery('english', ?))) AS score " +
                "FROM public.embeddings " +
                "WHERE text @@ plainto_tsquery('english', ?) OR embedding <-> ?::vector < 0.5 " +
                "ORDER BY score DESC LIMIT 50";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String text = rs.getString("text");
            String metadataJson = rs.getString("metadata");
            Metadata metadata = new Metadata();
            if (metadataJson != null && !metadataJson.isEmpty()) {
                try {
                    Map<String, Object> map = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
                    });
                    if (map != null) {
                        map.forEach((k, v) -> metadata.put(k, v != null ? v.toString() : ""));
                    }
                } catch (Exception e) {
                    log.error("Failed to parse metadata: {}", metadataJson, e);
                }
            }
            return Content.from(TextSegment.from(text, metadata));
        }, vectorStr, queryText, queryText, vectorStr);
    }
}
