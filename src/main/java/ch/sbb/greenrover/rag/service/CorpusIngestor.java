package ch.sbb.greenrover.rag.service;

import dev.langchain4j.data.document.Document;
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
    public void run(String... args) throws Exception {
        EmbeddingStoreIngestor ingestor = createIngestor();

        try (Scanner scanner = new Scanner(confluenceFileRes.getInputStream(), StandardCharsets.UTF_8)) {
            scanner.useDelimiter(SCANNER_DELIMITER);
            while (scanner.hasNext()) {
                processDocumentPart(scanner.next(), ingestor);
            }
        }
    }

    private EmbeddingStoreIngestor createIngestor() {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1000, 150))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
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


