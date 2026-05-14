package ch.sbb.tms.ssp.chat.controller;

import ch.sbb.tms.ssp.chat.config.properties.RagProperties;
import ch.sbb.tms.ssp.chat.scraper.BitbucketMarkdownScraper;
import ch.sbb.tms.ssp.chat.scraper.ConfluenceIncrementalScraper;
import ch.sbb.tms.ssp.chat.scraper.GithubMarkdownScraper;
import ch.sbb.tms.ssp.chat.scraper.ScraperOrchestrator;
import ch.sbb.tms.ssp.chat.service.BedrockMediaTranslationService;
import ch.sbb.tms.ssp.chat.service.DocumentBuilderService;
import ch.sbb.tms.ssp.chat.service.DocumentTranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@RestController
@RequestMapping("/api/admin/scraper")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class ScraperAdminController {

    private final ScraperOrchestrator scraperOrchestrator;
    private final GithubMarkdownScraper githubMarkdownScraper;
    private final BitbucketMarkdownScraper bitbucketMarkdownScraper;
    private final ConfluenceIncrementalScraper confluenceIncrementalScraper;
    private final DocumentBuilderService corpusBuilderService;
    private final BedrockMediaTranslationService mediaTranslationService;
    private final DocumentTranslationService documentTranslationService;
    private final RagProperties ragProperties;

    @PostMapping("/erase-export-dir")
    public ResponseEntity<String> eraseExportDir() {
        log.info("REST request to erase export directory: {}", ragProperties.getData().getExportDir());
        try {
            Path exportPath = Paths.get(ragProperties.getData().getExportDir());
            if (Files.exists(exportPath)) {
                FileSystemUtils.deleteRecursively(exportPath);
                log.info("Export directory erased.");
                return ResponseEntity.ok("Export directory erased successfully.");
            } else {
                log.info("Export directory does not exist, skipping erasure.");
                return ResponseEntity.ok("Export directory does not exist.");
            }
        } catch (Exception e) {
            log.error("Failed to erase export directory", e);
            return ResponseEntity.internalServerError().body("Failed to erase export directory: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    @PostMapping("/scrape-all")
    public ResponseEntity<String> scrapeAll() {
        log.info("REST request to run all scraper tasks...");
        try {
            scraperOrchestrator.runAllScrapers();
            return ResponseEntity.ok("All scraper tasks finished.");
        } catch (Exception e) {
            log.error("Failed to run all scrapers", e);
            return ResponseEntity.internalServerError().body("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/sync-github")
    public ResponseEntity<String> syncGithub() {
        log.info("REST request to sync Github...");
        try {
            githubMarkdownScraper.scrape();
            return ResponseEntity.ok("Github sync finished.");
        } catch (Exception e) {
            log.error("Failed to sync Github", e);
            return ResponseEntity.internalServerError().body("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/sync-bitbucket")
    public ResponseEntity<String> syncBitbucket() {
        log.info("REST request to sync Bitbucket...");
        try {
            bitbucketMarkdownScraper.scrape();
            return ResponseEntity.ok("Bitbucket sync finished.");
        } catch (Exception e) {
            log.error("Failed to sync Bitbucket", e);
            return ResponseEntity.internalServerError().body("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/sync-confluence")
    public ResponseEntity<String> syncConfluence() {
        log.info("REST request to sync Confluence...");
        try {
            confluenceIncrementalScraper.scrape();
            return ResponseEntity.ok("Confluence sync finished.");
        } catch (Exception e) {
            log.error("Failed to sync Confluence", e);
            return ResponseEntity.internalServerError().body("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/rebuild-rag")
    public ResponseEntity<String> rebuildRag() {
        log.info("REST request to rebuild RAG database...");
        try {
            corpusBuilderService.rebuildRag();
            return ResponseEntity.ok("RAG rebuild finished.");
        } catch (Exception e) {
            log.error("Failed to rebuild RAG", e);
            return ResponseEntity.internalServerError().body("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/translate-assets")
    public ResponseEntity<String> translateAllAssets() {
        log.info("REST request to translate assets...");
        try {
            mediaTranslationService.translateAllAssets();
            documentTranslationService.translateAllMarkdowns();
            return ResponseEntity.ok("All images and markdowns was translated.");
        } catch (Exception e) {
            log.error("Failed to translate images", e);
            return ResponseEntity.internalServerError().body("Failed: " + e.getMessage());
        }
    }
}

