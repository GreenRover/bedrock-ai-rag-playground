package ch.sbb.tms.ssp.chat.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@SuppressWarnings("NullableProblems")
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestor {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final JdbcTemplate jdbcTemplate;

    private EmbeddingStoreIngestor ingestor;

    private static final Pattern URL_PATTERN = Pattern.compile("url:\\s*'?(https?://[^'\\s]+)'?");
    private static final Pattern TITLE_PATTERN = Pattern.compile("title:\\s*'?([^'\\n]*)'?");
    private static final Pattern TITLE_PATH_PATTERN = Pattern.compile("title_path:\\s*'?([^'\\n]*)'?");
    private static final Pattern OUTBOUND_LINKS_PATTERN = Pattern.compile("outbound_links:\\s*'?([^'\\n]*)'?");
    private static final Pattern LAST_UPDATED_PATTERN = Pattern.compile("last_updated:\\s*'?([^'\\n]*)'?");

    @PostConstruct
    public void init() {
        log.info("Initializing EmbeddingStoreIngestor with Markdown-aware chunking strategy.");
        this.ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(new MarkdownDocumentSplitter(1000, 150))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    public void ingestDocument(String filename, String content) {
        if (shouldSkip(content)) {
            return;
        }

        Metadata metadata = extractMetadata(content);
        metadata.put("file_name", filename);

        String text = extractCleanText(content);

        if (text.length() >= 100) {
            Document document = Document.document(text, metadata);
            ingestor.ingest(document);
        }
    }

    public void truncateTable() {
        log.info("Truncating existing embeddings table...");
        jdbcTemplate.execute("TRUNCATE TABLE " + PostgresHybridRetriever.TABLE_NAME);
    }

    /**
     * Custom Markdown-aware DocumentSplitter that splits primarily by Markdown headers
     * and paragraphs, while enforcing maximum segment size constraints.
     */
    static class MarkdownDocumentSplitter implements DocumentSplitter {
        private final int maxSegmentSize;
        private final DocumentSplitter recursiveFallback;

        MarkdownDocumentSplitter(int maxSegmentSize, int maxOverlapSize) {
            this.maxSegmentSize = maxSegmentSize;
            this.recursiveFallback = DocumentSplitters.recursive(maxSegmentSize, maxOverlapSize);
        }

        @Override
        public List<TextSegment> split(Document document) {
            log.debug("Splitting document with Markdown-aware strategy. Length: {}", document.text().length());
            String text = document.text();

            // Basic splitting that captures hierarchy
            String[] parts = text.split("(?m)(?=^#+ )");

            List<TextSegment> allSegments = new ArrayList<>();
            String lastH1 = null;
            String lastH2 = null;

            for (String part : parts) {
                if (part.isBlank()) continue;

                String parentContext = "";
                if (part.startsWith("# ")) {
                    lastH1 = part.substring(2, part.indexOf('\n') > 0 ? part.indexOf('\n') : part.length()).trim();
                    lastH2 = null;
                } else if (part.startsWith("## ")) {
                    lastH2 = part.substring(3, part.indexOf('\n') > 0 ? part.indexOf('\n') : part.length()).trim();
                    if (lastH1 != null) parentContext = lastH1;
                } else if (part.startsWith("### ")) {
                    if (lastH1 != null) parentContext = lastH1;
                    if (lastH2 != null) parentContext += " > " + lastH2;
                }

                Metadata metadata = document.metadata().copy();
                if (!parentContext.isEmpty()) {
                    metadata.put("parent_context", parentContext);
                }

                // ====================================================================
                // NEW: Inject breadcrumbs and title path directly into the chunk text
                // ====================================================================
                String titlePath = metadata.getString("title_path");
                StringBuilder contextHeader = new StringBuilder();

                if (titlePath != null && !titlePath.isBlank()) {
                    contextHeader.append("Document Context: ").append(titlePath).append("\n");
                }
                if (!parentContext.isEmpty()) {
                    contextHeader.append("Section Hierarchy: ").append(parentContext).append("\n");
                }

                String enrichedPart = part;
                if (!contextHeader.isEmpty()) {
                    // Prepend the contextual headers to the actual chunk text
                    enrichedPart = contextHeader + "\n" + part;
                }
                // ====================================================================

                // Use the 'enrichedPart' for length checks and TextSegment generation
                if (enrichedPart.length() > maxSegmentSize) {
                    Document subDoc = Document.document(enrichedPart, metadata);
                    allSegments.addAll(recursiveFallback.split(subDoc));
                } else {
                    allSegments.add(TextSegment.from(enrichedPart, metadata));
                }
            }
            log.debug("Document split into {} segments.", allSegments.size());
            return allSegments;
        }
    }


    private boolean shouldSkip(String part) {
        String trimmed = part.trim();
        return trimmed.isEmpty() ||
                trimmed.startsWith("# KOMPLETTE DOKUMENTATION") ||
                trimmed.toLowerCase().contains("post mortem");
    }

    private Metadata extractMetadata(String part) {
        Metadata metadata = new Metadata();

        Matcher urlMatcher = URL_PATTERN.matcher(part);
        if (urlMatcher.find()) {
            metadata.put("url", urlMatcher.group(1));
        }

        Matcher titleMatcher = TITLE_PATTERN.matcher(part);
        if (titleMatcher.find()) {
            metadata.put("title", titleMatcher.group(1).trim());
        }

        Matcher titlePathMatcher = TITLE_PATH_PATTERN.matcher(part);
        if (titlePathMatcher.find()) {
            metadata.put("title_path", titlePathMatcher.group(1).trim());
        }

        Matcher outboundLinksMatcher = OUTBOUND_LINKS_PATTERN.matcher(part);
        if (outboundLinksMatcher.find()) {
            metadata.put("outbound_links", outboundLinksMatcher.group(1));
        }

        Matcher lastUpdatedMatcher = LAST_UPDATED_PATTERN.matcher(part);
        if (lastUpdatedMatcher.find()) {
            metadata.put("last_updated", lastUpdatedMatcher.group(1));
        }

        return metadata;
    }

    private String extractCleanText(String part) {
        int firstDash = part.indexOf("---");
        if (firstDash != -1) {
            int secondDash = part.indexOf("---", firstDash + 3);
            if (secondDash != -1) {
                return part.substring(secondDash + 3).trim();
            }
        }
        return part.trim();
    }
}


