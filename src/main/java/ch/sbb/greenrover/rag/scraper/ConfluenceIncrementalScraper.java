package ch.sbb.greenrover.rag.scraper;

import ch.sbb.greenrover.rag.service.ConfluenceClient;
import ch.sbb.greenrover.rag.service.BedrockMediaTranslationService;
import ch.sbb.greenrover.rag.service.ConfluenceToMarkdownService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static ch.sbb.greenrover.rag.service.ConfluenceToMarkdownService.ATTACHMENT_PREFIX;
import static ch.sbb.greenrover.rag.service.ConfluenceToMarkdownService.ATTACHMENT_SUFFIX;

@SuppressWarnings("SpringAutowiredFieldsWarningInspection")
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfluenceIncrementalScraper implements ApplicationRunner {

    private static final String CONFLUENCE_STORAGE_VERSION_EXPAND = "?expand=version";
    private static final String CONFLUENCE_STORAGE_BODY_EXPAND = "?expand=body.storage";
    private static final String CONFLUENCE_CHILD_PAGE_LIMIT_100 = "/child/page?limit=100";
    private static final String CONFLUENCE_CHILD_ATTACHMENT_LIMIT_100 = "/child/attachment?limit=100";
    private static final String CONFLUENCE_API_CONTENT_PATH = "/rest/api/content/";

    @Value("${confluence.start-page-id}")
    private String START_PAGE_ID;

    @Value("${confluence.export-dir:messaging_support_export}")
    private String exportDirString;

    private Path EXPORT_DIR;
    private Path ASSETS_DIR;
    private Path STATE_FILE;
    private static final Path CORPUS_FILE = Path.of("src/main/resources/messaging_support_corpus.txt");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private Map<String, Integer> syncState = new HashMap<>();

    private final ConfluenceClient confluenceClient;

    private final BedrockMediaTranslationService bedrockMediaTranslationService;

    private final ConfluenceToMarkdownService confluenceToMarkdownService;

    @PostConstruct
    public void init() {
        EXPORT_DIR = Path.of(exportDirString);
        ASSETS_DIR = EXPORT_DIR.resolve("assets");
        STATE_FILE = EXPORT_DIR.resolve("sync_state.json");
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean runEverything = args.containsOption("scrape-all") || (args.containsOption("scrape") && Objects.requireNonNull(args.getOptionValues("scrape")).isEmpty());
        boolean resync = args.containsOption("resync-confluence");
        boolean translate = args.containsOption("translate-images");
        boolean rebuild = args.containsOption("rebuild-corpus");

        if (runEverything) {
            log.info("CLI argument detected. Running all scraper tasks...");
            run();
            System.exit(0);
            return;
        }

        if (resync || translate || rebuild) {
            log.info("CLI argument detected. Running specific scraper tasks...");
            Files.createDirectories(EXPORT_DIR);

            if (resync) {
                resyncConfluence();
            }
            if (translate) {
                translateImages();
            }
            if (rebuild) {
                rebuildCorpus();
            }
            System.exit(0);
        }
    }

    // Run once a week on Sunday at midnight
    @Scheduled(cron = "0 0 0 * * SUN")
    public void run() throws Exception {
        Files.createDirectories(EXPORT_DIR);
        resyncConfluence();
        translateImages();
        rebuildCorpus();
    }

    private void resyncConfluence() throws Exception {
        loadSyncState();
        log.info("Starting incremental sync from page {}", START_PAGE_ID);
        processPageAndChildren(START_PAGE_ID, null, 0, "");
        saveSyncState();
    }

    private void processPageAndChildren(String pageId, String parentId, int depth, String titlePath) throws Exception {
        // Fetch page metadata (ID, Title, Version) WITHOUT the heavy body
        JsonNode pageMeta = confluenceClient.getApiResult(CONFLUENCE_API_CONTENT_PATH + pageId + CONFLUENCE_STORAGE_VERSION_EXPAND);
        int remoteVersion = pageMeta.at("/version/number").asInt();
        String title = pageMeta.get("title").asText();
        String currentTitlePath = titlePath == null || titlePath.isEmpty() ? title : titlePath + " > " + title;

        int localVersion = syncState.getOrDefault(pageId, 0);

        // Incremental Check: Only fetch content if there is a new version
        if (remoteVersion > localVersion) {
            log.info("-> Fetching new/updated content for: {} (v{})", title, remoteVersion);

            // Fetch heavy content payload
            JsonNode pageContent = confluenceClient.getApiResult(CONFLUENCE_API_CONTENT_PATH + pageId + CONFLUENCE_STORAGE_BODY_EXPAND);
            String htmlContent = pageContent.at("/body/storage/value").asText();

            // Convert HTML to clean text/markdown
            ConfluenceToMarkdownService.MarkdownResult markdownResult = confluenceToMarkdownService.convertToMarkdown(htmlContent, pageId);
            String markdownBody = markdownResult.markdown();

            String url = confluenceClient.getBaseUrl().replaceAll("/wiki/?$", "") + pageMeta.at("/_links/webui").asText("");

            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("title: '").append(title.replace("'", "''")).append("'\n");
            sb.append("title_path: '").append(currentTitlePath.replace("'", "''")).append("'\n");
            sb.append("page_id: '").append(pageId).append("'\n");
            sb.append("parent_page_id: '").append(parentId != null ? parentId : "null").append("'\n");
            sb.append("depth: ").append(depth).append("\n");
            sb.append("url: ").append(url).append("\n");
            if (!markdownResult.outboundLinks().isEmpty()) {
                sb.append("outbound_links: '").append(String.join(", ", markdownResult.outboundLinks()).replace("'", "''")).append("'\n");
            }
            sb.append("---\n\n");
            sb.append(markdownBody.isEmpty() ? "*NO CONTENT*" : markdownBody);

            // Save to local cache
            String safeTitle = title.replaceAll("[\\\\/*?:\"<>|]", "").replace(" ", "_");
            Path filePath = EXPORT_DIR.resolve(pageId + "_" + safeTitle + ".md");
            Files.writeString(filePath, sb.toString(), StandardCharsets.UTF_8);

            // Download attachments
            downloadAttachments(pageId);

            // Update state
            syncState.put(pageId, remoteVersion);
        } else {
            log.debug("-> Skipping unchanged: {} (v{})", title, localVersion);
        }

        // Recursively fetch children metadata
        JsonNode children = confluenceClient.getApiResult(CONFLUENCE_API_CONTENT_PATH + pageId + CONFLUENCE_CHILD_PAGE_LIMIT_100);
        for (JsonNode child : children.get("results")) {
            processPageAndChildren(child.get("id").asText(), pageId, depth + 1, currentTitlePath);
        }
    }

    private void downloadAttachments(String pageId) throws Exception {
        JsonNode attachments = confluenceClient.getApiResult(CONFLUENCE_API_CONTENT_PATH + pageId + CONFLUENCE_CHILD_ATTACHMENT_LIMIT_100);
        if (attachments.has("results")) {
            for (JsonNode attachment : attachments.get("results")) {
                String title = attachment.get("title").asText();
                String downloadUrl = attachment.at("/_links/download").asText();

                Path pageAssetsDir = ASSETS_DIR.resolve(pageId);
                Files.createDirectories(pageAssetsDir);

                Path targetFile = pageAssetsDir.resolve(title);

                if (Files.exists(targetFile)) {
                    log.debug("Attachment already exists, skipping: {}", title);
                    continue;
                }

                log.info("Downloading attachment: {}", title);

                confluenceClient.downloadAttachment(downloadUrl, targetFile);
                log.info("Saved attachment to {}", targetFile);
            }
        }
    }

    private void translateImages() throws IOException {
        if (!Files.exists(ASSETS_DIR)) return;

        log.info("\nTranslating images in: {}...", ASSETS_DIR.getFileName());

        try (Stream<Path> paths = Files.walk(ASSETS_DIR)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().endsWith(".md"))
                    .forEach(assetPath -> {
                        Path textFile = assetPath.resolveSibling(assetPath.getFileName() + ".md");
                        if (!Files.exists(textFile)) {
                            bedrockMediaTranslationService.extractTextWithBedrock(assetPath, assetPath.getFileName().toString(), assetPath.getParent());
                        } else {
                            log.debug("Attachment already translated, skipping: {}", assetPath.getFileName());
                        }
                    });
        }
    }

    private void rebuildCorpus() throws IOException {
        log.info("\nRebuilding Corpus file: {}...", CORPUS_FILE.getFileName());

        try (var writer = Files.newBufferedWriter(CORPUS_FILE, StandardCharsets.UTF_8);
             Stream<Path> paths = Files.walk(EXPORT_DIR)) {

            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getParent().equals(EXPORT_DIR))
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(txtPath -> {
                        try {
                            String content = Files.readString(txtPath, StandardCharsets.UTF_8);
                            content = replaceAttachmentPlaceholdersWithAttachmentDescription(txtPath, content);

                            writer.write("\n" + "=".repeat(70) + "\n");
                            writer.write("=== FILE: " + txtPath.getFileName().toString() + " ===\n");
                            writer.write("=".repeat(70) + "\n\n");

                            writer.write(content);
                            writer.write("\n\n");
                        } catch (IOException e) {
                            log.error("Error reading file: {}", txtPath, e);
                        }
                    });

            double sizeMb = Files.size(CORPUS_FILE) / (1024.0 * 1024.0);
            log.info("Done! Corpus was created: {} ({} MB)", CORPUS_FILE.getFileName(), sizeMb);
        }
    }

    private String replaceAttachmentPlaceholdersWithAttachmentDescription(Path txtPath, String content) throws IOException {
        String fileName = txtPath.getFileName().toString();
        int underscoreIndex = fileName.indexOf('_');
        if (underscoreIndex == -1) {
            return content;
        }

        String pageId = fileName.substring(0, underscoreIndex);
        Path pageAssetsDir = ASSETS_DIR.resolve(pageId);
        if (!Files.exists(pageAssetsDir)) {
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

    private void loadSyncState() {
        if (Files.exists(STATE_FILE)) {
            try {
                syncState = objectMapper.readValue(STATE_FILE.toFile(), new TypeReference<>() {
                });
            } catch (Exception e) {
                log.warn("Could not load sync state, starting fresh.", e);
            }
        }
    }

    private void saveSyncState() throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(STATE_FILE.toFile(), syncState);
    }
}
