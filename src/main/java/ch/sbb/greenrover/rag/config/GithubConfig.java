package ch.sbb.greenrover.rag.config;

import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.io.IOException;

@Configuration
@EnableRetry
public class GithubConfig {

    @Bean
    public GitHub gitHub(GithubProperties properties) throws IOException {
        GitHubBuilder builder = new GitHubBuilder();
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            builder.withOAuthToken(properties.getToken());
        }
        return builder.build();
    }

    @Bean
    public RestClient githubRestClient() {
        return RestClient.create();
    }

    @Bean
    public RetryTemplate githubRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .fixedBackoff(2000)
                .retryOn(Exception.class)
                .build();
    }
}
