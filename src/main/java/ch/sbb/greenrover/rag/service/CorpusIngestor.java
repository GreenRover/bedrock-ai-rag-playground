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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CorpusIngestor implements CommandLineRunner {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final Resource confluenceFileRes;

    public CorpusIngestor(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("classpath:messaging_support_corpus.txt") Resource confluenceFileRes) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.confluenceFileRes = confluenceFileRes;
    }

    @Override
    public void run(String... args) throws Exception {
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1000, 150))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        try (java.util.Scanner scanner = new java.util.Scanner(confluenceFileRes.getInputStream(), StandardCharsets.UTF_8)) {
            scanner.useDelimiter("(?m)^={70}\\r?\\n=== FILE: ");
            Pattern urlPattern = Pattern.compile("url: (https?://[^\\s]+)");
            Pattern titlePattern = Pattern.compile("title: (.*)");

            while (scanner.hasNext()) {
                String part = scanner.next();
                if (part.trim().isEmpty() || part.startsWith("# KOMPLETTE DOKUMENTATION")) continue;

                if (part.toLowerCase().contains("post mortem")) continue;

                String url = "";
                Matcher m = urlPattern.matcher(part);
                if (m.find()) {
                    url = m.group(1);
                }

                String title = "";
                Matcher mTitle = titlePattern.matcher(part);
                if (mTitle.find()) {
                    title = mTitle.group(1).trim();
                }

                Metadata metadata = new Metadata();
                if (!url.isEmpty()) {
                    metadata.put("url", url);
                }
                if (!title.isEmpty()) {
                    metadata.put("title", title);
                }

                String text = part;
                int firstDash = text.indexOf("---");
                if (firstDash != -1) {
                    int secondDash = text.indexOf("---", firstDash + 3);
                    if (secondDash != -1) {
                        text = text.substring(secondDash + 3).trim();
                    } else {
                        text = text.replaceFirst("(?s)^.*?===\\r?\\n={70}\\r?\\n", "").trim();
                    }
                } else {
                    text = text.replaceFirst("(?s)^.*?===\\r?\\n={70}\\r?\\n", "").trim();
                }

                if (text.length() < 100) continue;
                
                Document document = Document.document(text, metadata);
                ingestor.ingest(document);
            }
        }
    }


}


