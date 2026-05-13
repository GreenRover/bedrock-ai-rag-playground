package ch.sbb.tms.ssp.chat.scraper;

import ch.sbb.tms.ssp.chat.config.properties.GithubProperties;
import ch.sbb.tms.ssp.chat.service.ConfluenceToMarkdownService;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.VisitHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class GithubMarkdownScraper implements DocumentScraper {

    private static final String ASSETS_DIR = "assets";
    private static final String GH_PREFIX = "gh-";
    private static final String MD_EXTENSION = ".md";
    private static final String ADOC_EXTENSION = ".adoc";
    private static final String FRONTMATTER_DASHES = "---\n";

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final GitHub gitHub;
    private final RestClient githubRestClient;
    private final RetryTemplate githubRetryTemplate;
    private final GithubProperties githubProperties;

    @Value("${rag.data.export-dir:data_export}")
    private String exportDirString;

    @Override
    public void scrape() {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Scrape is already running for {}, skipping...", this.getClass().getSimpleName());
            return;
        }

        try {
            List<String> repositoryUrls = githubProperties.getRepositories();
            String githubToken = githubProperties.getToken();

            if (repositoryUrls == null || repositoryUrls.isEmpty()) {
                log.info("No GitHub repositories configured, skipping GitHub scraping.");
                return;
            }

            if (githubToken == null || githubToken.isBlank()) {
                log.warn("GITHUB_TOKEN is not set, GitHub scraping might fail due to rate limits.");
            }

            Path exportDir = Path.of(exportDirString);
            Files.createDirectories(exportDir);

            log.info("Connecting to GitHub with {} repositories configured...", repositoryUrls.size());

            for (String url : repositoryUrls) {
                try {
                    githubRetryTemplate.execute(context -> {
                        scrapeRepository(url, exportDir);
                        return null;
                    });
                } catch (Exception e) {
                    log.error("Error scraping repository {}: {}", url, e.getMessage());
                    log.debug("Detailed error scraping repository:", e);
                }
            }
            log.info("Scraping completed for {}.", this.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("Failed to execute GitHub scraping: {}", e.getMessage());
            log.debug("Detailed error in scrape():", e);
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
                lowerPath.startsWith(".agents/") ||
                lowerPath.startsWith(".github/");
    }

    private void scrapeRepository(String url, Path exportDir) throws IOException {
        String repoFullName = url.replace("https://github.com/", "");
        if (repoFullName.endsWith("/")) {
            repoFullName = repoFullName.substring(0, repoFullName.length() - 1);
        }

        GHRepository repository = gitHub.getRepository(repoFullName);
        String defaultBranch = repository.getDefaultBranch();
        log.info("Scraping repository: {} (default branch: {})", repoFullName, defaultBranch);

        GHTree tree = repository.getTreeRecursive(defaultBranch, 1);
        for (GHTreeEntry entry : tree.getTree()) {
            if ("blob".equals(entry.getType()) && (entry.getPath().endsWith(MD_EXTENSION) || entry.getPath().endsWith(ADOC_EXTENSION)) && !isExcluded(entry.getPath())) {
                processMarkdownFile(repository, entry, repoFullName, exportDir);
            }
        }
    }

    private void processMarkdownFile(GHRepository repository, GHTreeEntry entry, String repoFullName, Path exportDir) throws IOException {
        String path = entry.getPath();
        log.info("Processing file: {} in {}", path, repoFullName);

        GHContent content = repository.getFileContent(path);
        String rawContent;
        try (var is = content.read()) {
            rawContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        String processedContent = path.endsWith(ADOC_EXTENSION)
                ? rawContent
                : processImagesAndReplace(repository, path, rawContent, repoFullName, exportDir);

        String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        String githubFileUrl = content.getHtmlUrl();
        String lastUpdated = repository.getPushedAt() != null ? repository.getPushedAt().toInstant().toString() : "";

        StringBuilder sb = new StringBuilder();
        sb.append(FRONTMATTER_DASHES);
        sb.append("title: '").append(filename).append("'\n");
        sb.append("url: '").append(githubFileUrl).append("'\n");
        if (!lastUpdated.isEmpty()) {
            sb.append("last_updated: '").append(lastUpdated).append("'\n");
        }
        sb.append(FRONTMATTER_DASHES).append("\n");
        sb.append(processedContent);

        String repoSafeName = GH_PREFIX + repoFullName.replace("/", "-");
        String safePath = path.replace("/", "_");
        Path targetFile = exportDir.resolve(repoSafeName + "_" + safePath);

        Files.writeString(targetFile, sb.toString(), StandardCharsets.UTF_8);
        log.info("Saved to: {}", targetFile);
    }

    private record ImageReplacement(int startOffset, int endOffset, String safeName) {
    }

    @SuppressWarnings("DuplicatedCode")
    private String processImagesAndReplace(GHRepository repository, String mdPath, String content, String repoFullName, Path exportDir) {
        String repoSafeName = GH_PREFIX + repoFullName.replace("/", "-");
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
                    String safeImageName = processImage(repository, mdPath, url, assetsDir);
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
    private String processImage(GHRepository repository, String mdPath, String url, Path assetsDir) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return githubRetryTemplate.execute(context -> {
                try {
                    return downloadExternalImage(url, assetsDir);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            String resolvedPath = resolveRelativePath(mdPath, url);
            return githubRetryTemplate.execute(context -> {
                try {
                    return downloadGitHubImage(repository, resolvedPath, assetsDir);
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

    private String downloadGitHubImage(GHRepository repository, String path, Path assetsDir) throws IOException {
        String safeName = path.replace("/", "_");
        GHContent content = repository.getFileContent(path);
        if (content.isFile()) {
            Path target = assetsDir.resolve(safeName);
            if (Files.exists(target)) {
                log.debug("GitHub image already exists, skipping: {}", path);
                return safeName;
            }
            try (var is = content.read()) {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("Downloaded GitHub image: {} to {}", path, target);
            }
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

        ResponseEntity<byte[]> response = githubRestClient.get()
                .uri(url)
                .retrieve()
                .toEntity(byte[].class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            Files.write(target, response.getBody());
            log.info("Downloaded external image: {} to {}", url, target);
        } else {
            log.warn("Failed to download external image: {} (Status: {})", url, response.getStatusCode());
            Files.deleteIfExists(target);
            throw new IOException("Failed to download image " + url + " - status: " + response.getStatusCode());
        }
        return filename;
    }
}
