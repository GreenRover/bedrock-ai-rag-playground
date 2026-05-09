package ch.sbb.tms.ssp.chat.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "confluence")
public class ConfluenceProperties {
    private String baseUrl;
    private String token;
    private List<String> startPageId;
}
