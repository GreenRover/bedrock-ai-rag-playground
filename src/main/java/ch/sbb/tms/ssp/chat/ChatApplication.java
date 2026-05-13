package ch.sbb.tms.ssp.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Properties;

@SpringBootApplication
@EnableScheduling
public class ChatApplication {

    public static final String ARG_SCRAPE_ALL = "scrape-all";
    public static final String ARG_SCRAPE = "scrape";
    public static final String ARG_SYNC_CONFLUENCE = "sync-confluence";
    public static final String ARG_SYNC_GITHUB = "sync-github";
    public static final String ARG_SYNC_BITBUCKET = "sync-bitbucket";
    public static final String ARG_REBUILD_RAG = "rebuild-rag";
    public static final String ARG_ERASE_EXPORT_DIR = "erase-export-dir";

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ChatApplication.class);
        boolean isCliMode = false;

        for (String arg : args) {
            if (arg.contains(ARG_SCRAPE_ALL) ||
                    arg.contains(ARG_SCRAPE) ||
                    arg.contains(ARG_SYNC_CONFLUENCE) ||
                    arg.contains(ARG_SYNC_GITHUB) ||
                    arg.contains(ARG_SYNC_BITBUCKET) ||
                    arg.contains(ARG_REBUILD_RAG) ||
                    arg.contains(ARG_ERASE_EXPORT_DIR)
            ) {
                app.setWebApplicationType(WebApplicationType.NONE);
                isCliMode = true;
                break;
            }
        }

        // Disable Solace KPI metrics export when running offline CLI tasks
        if (isCliMode) {
            Properties defaultProperties = new Properties();
            // Disable Solace KPI and Spring Security when running offline CLI tasks
            defaultProperties.put("spring.autoconfigure.exclude",
                    "ch.sbb.micrometer.solacekpi.configuration.SolaceKpiMetricsExportAutoConfiguration," +
                            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
                            "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration");
            app.setDefaultProperties(defaultProperties);
        }

        app.run(args);
    }
}
