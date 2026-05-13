package ch.sbb.tms.ssp.chat.scraper;

import ch.sbb.tms.ssp.chat.ChatApplication;
import ch.sbb.tms.ssp.chat.service.DocumentBuilderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.FileSystemUtils;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentScraperRunner implements ApplicationRunner {

    @Lazy
    private final ScraperOrchestrator scraperOrchestrator;
    @Lazy
    private final GithubMarkdownScraper githubMarkdownScraper;
    @Lazy
    private final BitbucketMarkdownScraper bitbucketMarkdownScraper;
    @Lazy
    private final ConfluenceIncrementalScraper confluenceIncrementalScraper;
    private final DocumentBuilderService corpusBuilderService;

    @Value("${rag.data.export-dir:data_export}")
    private String exportDir;

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean scrapeAll = args.containsOption(ChatApplication.ARG_SCRAPE_ALL) || (args.containsOption(ChatApplication.ARG_SCRAPE) && Objects.requireNonNull(args.getOptionValues(ChatApplication.ARG_SCRAPE)).isEmpty());
            boolean syncGithub = args.containsOption(ChatApplication.ARG_SYNC_GITHUB);
            boolean syncBitbucket = args.containsOption(ChatApplication.ARG_SYNC_BITBUCKET);
            boolean resync = args.containsOption(ChatApplication.ARG_SYNC_CONFLUENCE);
            boolean rebuild = args.containsOption(ChatApplication.ARG_REBUILD_RAG);
            boolean eraseExportDir = args.containsOption(ChatApplication.ARG_ERASE_EXPORT_DIR);

            if (eraseExportDir) {
                log.info("CLI argument --{} detected. Erasing export directory: {}", ChatApplication.ARG_ERASE_EXPORT_DIR, exportDir);
                Path exportPath = Paths.get(exportDir);
                if (java.nio.file.Files.exists(exportPath)) {
                    FileSystemUtils.deleteRecursively(exportPath);
                    log.info("Export directory erased.");
                } else {
                    log.info("Export directory does not exist, skipping erasure.");
                }

                // If only erase-export-dir was passed and no other scraping options, we might want to exit.
                if (!scrapeAll && !syncGithub && !syncBitbucket && !resync && !rebuild) {
                    System.exit(0);
                    return;
                }
            }

            if (scrapeAll) {
                log.info("CLI argument --{} detected. Running all scraper tasks via orchestrator...", ChatApplication.ARG_SCRAPE_ALL);
                scraperOrchestrator.runAllScrapers();
                log.info("All scraper tasks finished. Exiting.");
                System.exit(0);
                return;
            }

            if (syncGithub || syncBitbucket || resync || rebuild) {
                log.info("CLI argument detected. Running specific tasks: syncGithub={}, syncBitbucket={}, resync={}, rebuild={}",
                        syncGithub, syncBitbucket, resync, rebuild);

                if (syncGithub) {
                    githubMarkdownScraper.scrape();
                }
                if (syncBitbucket) {
                    bitbucketMarkdownScraper.scrape();
                }
                if (resync) {
                    confluenceIncrementalScraper.scrape();
                }
                if (rebuild) {
                    corpusBuilderService.rebuildRag();
                }
                log.info("Requested CLI tasks completed. Exiting.");
                System.exit(0);
            }
        } catch (Exception e) {
            log.error("Error during DocumentScraperRunner execution: {}", e.getMessage());
            log.debug("Detailed error in DocumentScraperRunner:", e);
            System.exit(1);
        }
    }
}
