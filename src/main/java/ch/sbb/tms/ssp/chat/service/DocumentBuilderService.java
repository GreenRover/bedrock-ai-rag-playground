package ch.sbb.tms.ssp.chat.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static ch.sbb.tms.ssp.chat.service.ConfluenceToMarkdownService.ATTACHMENT_PREFIX;
import static ch.sbb.tms.ssp.chat.service.ConfluenceToMarkdownService.ATTACHMENT_SUFFIX;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentBuilderService {

    public final static String ENGLISH_CACHE_FILE_SUFFIX = ".en-cache";

    private final DocumentTranslationService documentTranslationService;
    private final DocumentIngestor documentIngestor;
    private final JdbcTemplate jdbcTemplate;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Value("${rag.data.export-dir:data_export}")
    private String exportDirString;

    private Path EXPORT_DIR;
    private Path ASSETS_DIR;

    @PostConstruct
    public void init() {
        EXPORT_DIR = Path.of(exportDirString);
        ASSETS_DIR = EXPORT_DIR.resolve("assets");
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
                        .filter(p -> !p.getFileName().toString().startsWith("00_index"))
                        .parallel()
                        .forEach(txtPath -> {
                            try {
                                String content = Files.readString(txtPath, StandardCharsets.UTF_8);
                                content = replaceAttachmentPlaceholdersWithAttachmentDescription(txtPath, content);

                                Path cachePath = Path.of(txtPath + ENGLISH_CACHE_FILE_SUFFIX);
                                if (Files.exists(cachePath) && Files.getLastModifiedTime(cachePath).compareTo(Files.getLastModifiedTime(txtPath)) > 0) {
                                    log.debug("Cache hit for {}", txtPath.getFileName().toString());
                                    content = Files.readString(cachePath, StandardCharsets.UTF_8);
                                } else {
                                    content = documentTranslationService.ensureEnglish(txtPath.getFileName().toString(), content);
                                    Files.writeString(cachePath, content, StandardCharsets.UTF_8);
                                }

                                documentIngestor.ingestDocument(txtPath.getFileName().toString(), content);
                            } catch (IOException e) {
                                log.error("Error reading file: {}", txtPath, e);
                            }
                        });

                log.info("Done! Corpus was ingested directly into the PostgresDB.");
            }
        } finally {
            isRunning.set(false);
        }
    }

    private String replaceAttachmentPlaceholdersWithAttachmentDescription(Path txtPath, String content) throws IOException {
        String fileName = txtPath.getFileName().toString();

        Path pageAssetsDir = null;
        try (Stream<Path> dirs = Files.list(ASSETS_DIR)) {
            Optional<Path> foundDir = dirs.filter(Files::isDirectory)
                    .filter(d -> fileName.startsWith(d.getFileName().toString() + "_"))
                    .max(Comparator.comparingInt(d -> d.getFileName().toString().length()));
            if (foundDir.isPresent()) {
                pageAssetsDir = foundDir.get();
            }
        }

        if (pageAssetsDir == null || !Files.exists(pageAssetsDir)) {
            return content;
        }

        try (Stream<Path> assetPaths = Files.list(pageAssetsDir)) {
            for (Path assetTxtPath : assetPaths.filter(p -> p.toString().endsWith(".md")).toList()) {
                String assetFileName = assetTxtPath.getFileName().toString().replace(".md", "");
                String assetContent = Files.readString(assetTxtPath, StandardCharsets.UTF_8);
                content = content.replace(ATTACHMENT_PREFIX + assetFileName + ATTACHMENT_SUFFIX,
                        "\n--- Asset Translation: " + assetFileName + " ---\n" + assetContent + "\n");
            }
        }
        return content;
    }
}
