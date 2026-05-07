package ch.sbb.greenrover.rag.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class CorpusIngestor implements CommandLineRunner {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final Resource confluenceFileRes;

    private static final Pattern URL_PATTERN = Pattern.compile("url: (https?://[^\\s]+)");
    private static final Pattern TITLE_PATTERN = Pattern.compile("title: (.*)");
    private static final Pattern TITLE_PATH_PATTERN = Pattern.compile("title_path: '(.*)'");
    private static final Pattern OUTBOUND_LINKS_PATTERN = Pattern.compile("outbound_links: '(.*)'");
    private static final String SCANNER_DELIMITER = "(?m)^={70}\\r?\\n=== FILE: ";

    public CorpusIngestor(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${confluence.corpus.file:classpath:messaging_support_corpus.txt}") Resource confluenceFileRes) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.confluenceFileRes = confluenceFileRes;
    }

    @Override
    public void run(String... args) {
        try {
            EmbeddingStoreIngestor ingestor = createIngestor();

            if (!confluenceFileRes.exists()) {
                log.warn("Corpus file not found: {}. Skipping ingestion.", confluenceFileRes.getDescription());
                return;
            }

            log.info("Starting corpus ingestion from {}...", confluenceFileRes.getDescription());
            try (Scanner scanner = new Scanner(confluenceFileRes.getInputStream(), StandardCharsets.UTF_8)) {
                scanner.useDelimiter(SCANNER_DELIMITER);
                int count = 0;
                while (scanner.hasNext()) {
                    processDocumentPart(scanner.next(), ingestor);
                    count++;
                }
                log.info("Corpus ingestion completed successfully. Processed {} parts.", count);
            }
        } catch (Exception e) {
            log.error("Failed to ingest corpus: {}. The application will continue, but RAG might not have latest data.", e.getMessage());
            log.debug("Detailed error during ingestion:", e);
        }
    }

    private EmbeddingStoreIngestor createIngestor() {
        log.info("Initializing EmbeddingStoreIngestor with Markdown-aware chunking strategy.");
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(new MarkdownDocumentSplitter(1000, 150))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    /**
     * Custom Markdown-aware DocumentSplitter that splits primarily by Markdown headers
     * and paragraphs, while enforcing maximum segment size constraints.
     */
    private static class MarkdownDocumentSplitter implements DocumentSplitter {
        private final int maxSegmentSize;
        private final DocumentSplitter recursiveFallback;

        MarkdownDocumentSplitter(int maxSegmentSize, int maxOverlapSize) {
            this.maxSegmentSize = maxSegmentSize;
            this.recursiveFallback = DocumentSplitters.recursive(maxSegmentSize, maxOverlapSize);
        }

        @Override
        public List<TextSegment> split(Document document) {
            log.debug("Splitting document with Markdown-aware strategy. Length: {}", document.text().length());
            // Split by Markdown headers (e.g., #, ##, ###) using a positive lookahead to keep headers with content
            String[] headerParts = document.text().split("(?m)(?=^#+ )");

            List<TextSegment> allSegments = new ArrayList<>();
            for (String part : headerParts) {
                if (part.isBlank()) continue;

                if (part.length() > maxSegmentSize) {
                    // If the section is too large, use recursive splitting for paragraphs and lines
                    Document subDoc = Document.document(part, document.metadata());
                    allSegments.addAll(recursiveFallback.split(subDoc));
                } else {
                    allSegments.add(TextSegment.from(part, document.metadata()));
                }
            }
            log.debug("Document split into {} segments.", allSegments.size());
            return allSegments;
        }
    }

    private void processDocumentPart(String part, EmbeddingStoreIngestor ingestor) {
        if (shouldSkip(part)) {
            return;
        }

        Metadata metadata = extractMetadata(part);
        String text = extractCleanText(part);

        if (text.length() >= 100) {
            Document document = Document.document(text, metadata);
            ingestor.ingest(document);
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
        return part.replaceFirst("(?s)^.*?===\\r?\\n={70}\\r?\\n", "").trim();
    }
}


