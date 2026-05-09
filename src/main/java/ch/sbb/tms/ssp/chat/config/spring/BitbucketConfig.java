package ch.sbb.tms.ssp.chat.config.spring;

import ch.sbb.tms.ssp.chat.config.properties.BitbucketProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class BitbucketConfig {

    private final BitbucketProperties properties;

    @Bean
    public RestClient bitbucketRestClient() {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getToken())
                .build();
    }
}
