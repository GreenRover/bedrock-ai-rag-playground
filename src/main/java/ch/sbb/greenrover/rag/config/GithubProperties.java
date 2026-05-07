package ch.sbb.greenrover.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "github")
public class GithubProperties {
    private String token;
    private List<String> repositories;
    private String exportDir = "messaging_support_export";
}
