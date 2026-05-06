package ch.sbb.greenrover.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(RagApplication.class);
        for (String arg : args) {
            if (arg.contains("scrape-all") || arg.contains("resync-confluence") ||
                    arg.contains("translate-images") || arg.contains("rebuild-corpus")) {
                app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
                break;
            }
        }
        app.run(args);
    }
}

