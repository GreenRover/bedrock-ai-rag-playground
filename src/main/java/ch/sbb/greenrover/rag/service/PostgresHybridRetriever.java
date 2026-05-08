package ch.sbb.greenrover.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
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

    public final static String TABLE_NAME = "embeddings";

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<Content> retrieve(Query query) {
        String queryText = query.text();
        dev.langchain4j.data.embedding.Embedding queryEmbedding = embeddingModel.embed(queryText).content();

        String vectorStr = java.util.Arrays.toString(queryEmbedding.vector());

        // Use a CTE to pull top 50 by vector, top 50 by text, then combine and re-score.
        // Also using <=> (Cosine Distance) instead of <-> (L2 Distance).
        String sql = """
            WITH semantic_search AS (
                SELECT embedding_id, text, metadata,
                       (1.0 - (embedding OPERATOR(public.<=>) ?::public.vector)) AS v_score
                FROM $table$
                ORDER BY embedding OPERATOR(public.<=>) ?::public.vector
                LIMIT 50
            ),
            keyword_search AS (
                SELECT embedding_id, text, metadata,
                       ts_rank_cd(to_tsvector('english', text), websearch_to_tsquery('english', ?)) AS k_score
                FROM $table$
                WHERE text @@ websearch_to_tsquery('english', ?)
                ORDER BY k_score DESC
                LIMIT 50
            )
            SELECT
                COALESCE(s.text, k.text) AS text,
                COALESCE(s.metadata, k.metadata) AS metadata,
                (COALESCE(s.v_score, 0.0) + COALESCE(k.k_score, 0.0)) AS score
            FROM semantic_search s
            FULL OUTER JOIN keyword_search k ON s.embedding_id = k.embedding_id
            ORDER BY score DESC
            LIMIT 50
            """.replace("$table$", TABLE_NAME);

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String text = rs.getString("text");
            String metadataJson = rs.getString("metadata");
            Metadata metadata = new Metadata();
            if (metadataJson != null && !metadataJson.isEmpty()) {
                try {
                    Map<String, Object> map = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
                    if (map != null) {
                        map.forEach((k, v) -> metadata.put(k, v != null ? v.toString() : ""));
                    }
                } catch (Exception e) {
                    log.error("Failed to parse metadata: {}", metadataJson, e);
                }
            }
            return Content.from(TextSegment.from(text, metadata));
        }, vectorStr, vectorStr, queryText, queryText);
    }
}
