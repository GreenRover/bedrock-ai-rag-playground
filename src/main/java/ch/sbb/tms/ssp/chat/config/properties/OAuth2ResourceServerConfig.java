package ch.sbb.tms.ssp.chat.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "auth.oauth2.resourceserver")
public class OAuth2ResourceServerConfig {
    private JwtConfig jwt;

    @Data
    public static class JwtConfig {
        private List<JwtIssuerConfig> issuer;
    }

    @Data
    public static class JwtIssuerConfig {
        private String uri;
        private JwtIssuerType type;
        private String identifier;
    }

    public enum JwtIssuerType {
        AZUREAD,
    }
}