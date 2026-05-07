package ch.sbb.greenrover.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RagApplication {

    public static final String ARG_SCRAPE_ALL = "scrape-all";
    public static final String ARG_SCRAPE = "scrape";
    public static final String ARG_RESYNC_CONFLUENCE = "resync-confluence";
    public static final String ARG_SYNC_GITHUB = "sync-github";
    public static final String ARG_TRANSLATE_IMAGES = "translate-images";
    public static final String ARG_REBUILD_CORPUS = "rebuild-corpus";

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(RagApplication.class);
        for (String arg : args) {
            if (arg.contains(ARG_SCRAPE_ALL) ||
                    arg.contains(ARG_SCRAPE) ||
                    arg.contains(ARG_RESYNC_CONFLUENCE) ||
                    arg.contains(ARG_SYNC_GITHUB) ||
                    arg.contains(ARG_TRANSLATE_IMAGES) ||
                    arg.contains(ARG_REBUILD_CORPUS)
            ) {
                app.setWebApplicationType(WebApplicationType.NONE);
                break;
            }
        }
        app.run(args);
    }
}

