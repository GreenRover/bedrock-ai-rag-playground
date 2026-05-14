package ch.sbb.tms.ssp.chat.scraper;

import ch.sbb.tms.ssp.chat.config.properties.BitbucketProperties;
import ch.sbb.tms.ssp.chat.config.properties.RagProperties;
import ch.sbb.tms.ssp.chat.service.ConfluenceToMarkdownService;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.VisitHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class BitbucketMarkdownScraper implements DocumentScraper {

    private static final String ASSETS_DIR = "assets";
    private static final String BB_PREFIX = "bb-";
    private static final String MD_EXTENSION = ".md";
    private static final String FRONTMATTER_DASHES = "---\n";

    private final BitbucketProperties bitbucketProperties;

    private final RagProperties ragProperties;
    private final RestClient bitbucketRestClient;
    private final RetryTemplate retryTemplate;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    public void scrape() {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Scrape is already running for {}, skipping...", this.getClass().getSimpleName());
            return;
        }

        try {
            List<String> repositoryUrls = bitbucketProperties.getRepositories();
            String token = bitbucketProperties.getToken();

            if (repositoryUrls == null || repositoryUrls.isEmpty()) {
                log.info("No Bitbucket repositories configured, skipping Bitbucket scraping.");
                return;
            }

            if (token == null || token.isBlank()) {
                log.warn("BITBUCKET_TOKEN is not set, Bitbucket scraping might fail.");
            }

            Path exportDir = Path.of(ragProperties.getData().getExportDir());
            Files.createDirectories(exportDir);

            log.info("Connecting to Bitbucket with {} repositories configured...", repositoryUrls.size());

            for (String url : repositoryUrls) {
                try {
                    retryTemplate.execute(context -> {
                        scrapeRepository(url, exportDir);
                        return null;
                    });
                } catch (Exception e) {
                    log.error("Error scraping repository {}: {}", url, e.getMessage());
                }
            }
            log.info("Scraping completed for {}.", this.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("Failed to execute Bitbucket scraping: {}", e.getMessage());
        } finally {
            isRunning.set(false);
        }
    }

    private boolean isExcluded(String path) {
        if (path == null) return false;
        String lowerPath = path.toLowerCase();
        return lowerPath.endsWith("code_of_conduct.md") ||
                lowerPath.endsWith("contributing.md") ||
                lowerPath.endsWith("agent.md") ||
                lowerPath.endsWith("changelog.md") ||
                lowerPath.contains("/.agents/") ||
                lowerPath.startsWith(".agents/");
    }

    private void scrapeRepository(String url, Path exportDir) throws IOException {
        String[] projectAndRepo = parseProjectAndRepo(url);
        String projectKey = projectAndRepo[0];
        String repositorySlug = projectAndRepo[1];

        if (projectKey == null || repositorySlug == null) {
            log.warn("Failed to parse projectKey or repositorySlug from URL: {}", url);
            return;
        }

        log.info("Scraping Bitbucket repository: {}/{}", projectKey, repositorySlug);

        // Fetch file tree
        String filesUri = String.format("/rest/api/1.0/projects/%s/repos/%s/files?limit=1000", projectKey, repositorySlug);
        ResponseEntity<String> response = bitbucketRestClient.get()
                .uri(filesUri)
                .retrieve()
                .toEntity(String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode values = root.path("values");
            if (values.isArray()) {
                for (JsonNode node : values) {
                    String filePath = node.asString();
                    if (filePath.endsWith(MD_EXTENSION) && !isExcluded(filePath)) {
                        processMarkdownFile(projectKey, repositorySlug, filePath, exportDir);
                    }
                }
            }
        }
    }

    private String[] parseProjectAndRepo(String url) {
        String[] parts = url.split("/");
        String projectKey = null;
        String repositorySlug = null;
        for (int i = 0; i < parts.length; i++) {
            if ("projects".equals(parts[i]) && i + 1 < parts.length) {
                projectKey = parts[i + 1];
            }
            if ("repos".equals(parts[i]) && i + 1 < parts.length) {
                repositorySlug = parts[i + 1];
            }
        }
        return new String[]{projectKey, repositorySlug};
    }

    private void processMarkdownFile(String projectKey, String repositorySlug, String filePath, Path exportDir) throws IOException {
        log.info("Processing file: {} in {}/{}", filePath, projectKey, repositorySlug);

        String rawUri = String.format("/rest/api/1.0/projects/%s/repos/%s/raw/%s", projectKey, repositorySlug, filePath);
        ResponseEntity<String> response;
        try {
            response = bitbucketRestClient.get()
                    .uri(rawUri)
                    .retrieve()
                    .toEntity(String.class);
        } catch (Exception e) {
            log.warn("Failed to download raw markdown file: {} due to {}", filePath, e.getMessage());
            return;
        }

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.warn("Failed to download raw markdown file: {} status {} location {}", filePath, response.getStatusCode(), response.getHeaders().getLocation());
            return;
        }

        String rawContent = response.getBody();
        String processedContent = processImagesAndReplace(projectKey, repositorySlug, filePath, rawContent, exportDir);

        String filename = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        String bitbucketFileUrl = String.format("%s/projects/%s/repos/%s/browse/%s", bitbucketProperties.getBaseUrl(), projectKey, repositorySlug, filePath);

        String sb = FRONTMATTER_DASHES +
                "title: '" + filename + "'\n" +
                "url: '" + bitbucketFileUrl + "'\n" +
                FRONTMATTER_DASHES + "\n" +
                processedContent;

        String safePath = filePath.replace("/", "_");
        Path targetFile = exportDir.resolve("bb_" + projectKey + "_" + repositorySlug + "_" + safePath);

        Files.writeString(targetFile, sb, StandardCharsets.UTF_8);
        log.info("Saved to: {}", targetFile);
    }

    private record ImageReplacement(int startOffset, int endOffset, String safeName) {
    }

    @SuppressWarnings("DuplicatedCode")
    private String processImagesAndReplace(String projectKey, String repositorySlug, String mdPath, String content, Path exportDir) {
        String repoSafeName = BB_PREFIX + projectKey + "-" + repositorySlug;
        Path assetsDir = exportDir.resolve(ASSETS_DIR).resolve(repoSafeName);
        try {
            Files.createDirectories(assetsDir);
        } catch (IOException e) {
            log.error("Could not create assets directory: {}", assetsDir, e);
            return content;
        }

        Parser parser = Parser.builder().build();
        Node document = parser.parse(content);

        List<ImageReplacement> replacements = new ArrayList<>();

        NodeVisitor visitor = new NodeVisitor(
                new VisitHandler<>(Image.class, image -> {
                    String url = image.getUrl().toString();
                    String safeImageName = processImage(projectKey, repositorySlug, mdPath, url, assetsDir);
                    if (safeImageName != null) {
                        replacements.add(new ImageReplacement(image.getStartOffset(), image.getEndOffset(), safeImageName));
                    }
                })
        );
        visitor.visit(document);

        replacements.sort(Comparator.comparingInt(ImageReplacement::startOffset).reversed());

        StringBuilder sb = new StringBuilder(content);
        for (ImageReplacement r : replacements) {
            sb.replace(r.startOffset(), r.endOffset(), ConfluenceToMarkdownService.ATTACHMENT_PREFIX + r.safeName() + ConfluenceToMarkdownService.ATTACHMENT_SUFFIX);
        }

        return sb.toString();
    }

    @SuppressWarnings("HttpUrlsUsage")
    private String processImage(String projectKey, String repositorySlug, String mdPath, String url, Path assetsDir) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return retryTemplate.execute(context -> {
                try {
                    return downloadExternalImage(url, assetsDir);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            String resolvedPath = resolveRelativePath(mdPath, url);
            return retryTemplate.execute(context -> {
                try {
                    return downloadBitbucketImage(projectKey, repositorySlug, resolvedPath, assetsDir);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private String resolveRelativePath(String mdPath, String imgPath) {
        if (imgPath.startsWith("/")) {
            return imgPath.substring(1);
        }

        StringBuilder parent = new StringBuilder();
        int lastSlash = mdPath.lastIndexOf('/');
        if (lastSlash != -1) {
            parent = new StringBuilder(mdPath.substring(0, lastSlash));
        }

        String[] parts = imgPath.split("/");
        for (String part : parts) {
            if (part.equals("..")) {
                int lastParentSlash = parent.toString().lastIndexOf('/');
                if (lastParentSlash != -1) {
                    parent = new StringBuilder(parent.substring(0, lastParentSlash));
                } else {
                    parent = new StringBuilder();
                }
            } else if (!part.equals(".")) {
                if (parent.isEmpty()) {
                    parent = new StringBuilder(part);
                } else {
                    parent.append("/").append(part);
                }
            }
        }
        return parent.toString();
    }

    private String downloadBitbucketImage(String projectKey, String repositorySlug, String path, Path assetsDir) throws IOException {
        String safeName = path.replace("/", "_");
        Path target = assetsDir.resolve(safeName);
        if (Files.exists(target)) {
            log.debug("Bitbucket image already exists, skipping: {}", path);
            return safeName;
        }

        String rawUri = String.format("/rest/api/1.0/projects/%s/repos/%s/raw/%s", projectKey, repositorySlug, path);
        ResponseEntity<byte[]> response;
        try {
            response = bitbucketRestClient.get()
                    .uri(rawUri)
                    .retrieve()
                    .toEntity(byte[].class);
        } catch (Exception e) {
            log.warn("Failed to download relative image: {}", path);
            return safeName;
        }

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Files.write(target, response.getBody());
            log.info("Downloaded Bitbucket image: {} to {}", path, target);
        }
        return safeName;
    }

    @SuppressWarnings("DuplicatedCode")
    private String downloadExternalImage(String url, Path assetsDir) throws IOException {
        String filename = url.substring(url.lastIndexOf('/') + 1);
        if (filename.contains("?")) {
            filename = filename.substring(0, filename.indexOf('?'));
        }
        if (filename.isEmpty()) {
            filename = "external_image_" + System.currentTimeMillis();
        }

        Path target = assetsDir.resolve(filename);
        if (Files.exists(target)) {
            log.debug("External image already exists, skipping: {}", url);
            return filename;
        }

        ResponseEntity<byte[]> response;
        try {
            response = bitbucketRestClient.get()
                    .uri(url)
                    .retrieve()
                    .toEntity(byte[].class);
        } catch (Exception e) {
            log.warn("Failed to download external image: {}", url);
            return filename;
        }

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Files.write(target, response.getBody());
            log.info("Downloaded external image: {} to {}", url, target);
        }
        return filename;
    }
}