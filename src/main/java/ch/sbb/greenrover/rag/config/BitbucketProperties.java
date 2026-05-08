package ch.sbb.greenrover.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "bitbucket")
public class BitbucketProperties {
    private String token;
    private List<String> repositories;
    private String baseUrl = "https://code.sbb.ch";
}
