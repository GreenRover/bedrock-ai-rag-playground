package ch.sbb.tms.ssp.chat.service;

import ch.sbb.tms.ssp.chat.config.properties.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentBuilderService {

    private final DocumentTranslationService documentTranslationService;
    private final DocumentIngestor documentIngestor;
    private final JdbcTemplate jdbcTemplate;
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

            log.info("Truncating existing embeddings table...");
            jdbcTemplate.execute("TRUNCATE TABLE " + PostgresHybridRetriever.TABLE_NAME);

            try (Stream<Path> paths = Files.walk(EXPORT_DIR)) {

                paths.filter(Files::isRegularFile)
                        .filter(p -> p.getParent().equals(EXPORT_DIR))
                        .filter(p -> p.toString().endsWith(".md"))
                        .parallel()
                        .forEach(markdownPath -> {
                            try {
                                String content = documentTranslationService.translateMarkdownToEnglishAndInjectAttachmentDescription(markdownPath);
                                documentIngestor.ingestDocument(markdownPath.getFileName().toString(), content);
                            } catch (IOException e) {
                                log.error("Error reading file: {}", markdownPath, e);
                            }
                        });

                log.info("Done! Corpus was ingested directly into the PostgresDB.");
            }
        } finally {
            isRunning.set(false);
        }
    }


}
