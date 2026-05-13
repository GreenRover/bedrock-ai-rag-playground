package ch.sbb.tms.ssp.chat.scraper;

import ch.sbb.tms.ssp.chat.service.BedrockMediaTranslationService;
import ch.sbb.tms.ssp.chat.service.DocumentBuilderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScraperOrchestrator {

    @Lazy
    private final List<DocumentScraper> scrapers;
    private final DocumentBuilderService corpusBuilderService;
    private final BedrockMediaTranslationService mediaTranslationService;

    // Run once a week on Sunday at midnight
    @Scheduled(cron = "0 0 5 * * SUN")
    public void runAllScrapers() {
        log.info("Starting scheduled scraping and corpus rebuilding task...");
        for (DocumentScraper scraper : scrapers) {
            try {
                log.info("Running scraper: {}", scraper.getClass().getSimpleName());
                scraper.scrape();
            } catch (Exception e) {
                log.error("Error running scraper: {}", scraper.getClass().getSimpleName(), e);
            }
        }

        try {
            log.info("Running global asset translation...");
            mediaTranslationService.translateAllAssets();
        } catch (Exception e) {
            log.error("Error during asset translation", e);
        }

        try {
            log.info("Running corpus builder...");
            corpusBuilderService.rebuildRag();
        } catch (Exception e) {
            log.error("Error rebuilding corpus", e);
        }
        log.info("Scheduled task completed.");
    }
}
