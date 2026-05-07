package ch.sbb.greenrover.rag.scraper;

import ch.sbb.greenrover.rag.RagApplication;
import ch.sbb.greenrover.rag.service.CorpusBuilderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private final ConfluenceIncrementalScraper confluenceIncrementalScraper;
    private final CorpusBuilderService corpusBuilderService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean scrapeAll = args.containsOption(RagApplication.ARG_SCRAPE_ALL) || (args.containsOption(RagApplication.ARG_SCRAPE) && Objects.requireNonNull(args.getOptionValues(RagApplication.ARG_SCRAPE)).isEmpty());
            boolean syncGithub = args.containsOption(RagApplication.ARG_SYNC_GITHUB);
            boolean resync = args.containsOption(RagApplication.ARG_RESYNC_CONFLUENCE);
            boolean translate = args.containsOption(RagApplication.ARG_TRANSLATE_IMAGES);
            boolean rebuild = args.containsOption(RagApplication.ARG_REBUILD_CORPUS);

            if (scrapeAll) {
                log.info("CLI argument --{} detected. Running all scraper tasks via orchestrator...", RagApplication.ARG_SCRAPE_ALL);
                scraperOrchestrator.runAllScrapers();
                log.info("All scraper tasks finished. Exiting.");
                System.exit(0);
                return;
            }

            if (syncGithub || resync || translate || rebuild) {
                log.info("CLI argument detected. Running specific tasks: syncGithub={}, resync={}, translate={}, rebuild={}",
                        syncGithub, resync, translate, rebuild);

                if (syncGithub) {
                    githubMarkdownScraper.scrape();
                }
                if (resync) {
                    confluenceIncrementalScraper.scrape();
                }
                if (translate) {
                    confluenceIncrementalScraper.translateImages();
                }
                if (rebuild) {
                    corpusBuilderService.rebuildCorpus();
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
