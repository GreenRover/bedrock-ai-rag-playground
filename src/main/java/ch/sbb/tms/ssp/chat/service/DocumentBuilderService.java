package ch.sbb.tms.ssp.chat.service;

import ch.sbb.tms.ssp.chat.config.properties.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.*;
import static java.util.concurrent.Executors.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentBuilderService {

    private final DocumentTranslationService documentTranslationService;
    private final DocumentIngestor documentIngestor;
    private final RagProperties ragProperties;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private Path EXPORT_DIR;

    @PostConstruct
    public void init() {
        EXPORT_DIR = Path.of(ragProperties.getData().getExportDir());
    }

    public void rebuildRag() throws IOException {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("RebuildRag is already running for {}, skipping...", this.getClass().getSimpleName());
            return;
        }

        try {
            log.info("\nRebuilding RAG and ingesting into PostgresDB ...");

            documentIngestor.truncateTable();

            try (Stream<Path> paths = Files.walk(EXPORT_DIR)) {

                List<Path> targetFiles = paths.filter(Files::isRegularFile)
                        .filter(p -> p.getParent().equals(EXPORT_DIR))
                        .filter(p -> p.toString().endsWith(".md"))
                        .toList();

                int total = targetFiles.size();
                AtomicInteger processed = new AtomicInteger(0);
                AtomicLong lastPrintTime = new AtomicLong(System.currentTimeMillis());

                try (ExecutorService executor = newFixedThreadPool(8)) {
                    List<CompletableFuture<Void>> futures = targetFiles.stream()
                            .map(markdownPath -> runAsync(() -> {
                                try {
                                    String content = documentTranslationService.translateMarkdownToEnglishAndInjectAttachmentDescription(markdownPath);
                                    documentIngestor.ingestDocument(markdownPath.getFileName().toString(), content);
                                } catch (IOException e) {
                                    log.error("Error reading file: {}", markdownPath, e);
                                }

                                synchronized (lastPrintTime) {
                                    int current = processed.incrementAndGet();
                                    long now = System.currentTimeMillis();
                                    long lastTime = lastPrintTime.get();
                                    if (now - lastTime > 60000 && lastPrintTime.compareAndSet(lastTime, now)) {
                                        log.info("Document ingestion progress: {}/{} completed", current, total);
                                    }
                                }
                            }, executor))
                            .toList();
                    allOf(futures.toArray(new CompletableFuture[0])).join();
                }

                log.info("Done! markdown files was ingested directly into the PostgresDB.");
            }
        } finally {
            isRunning.set(false);
        }
    }


}
