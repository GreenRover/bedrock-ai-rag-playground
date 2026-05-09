package ch.sbb.tms.ssp.chat.config.spring;

import ch.sbb.tms.ssp.chat.config.properties.OAuth2ResourceServerConfig;
import ch.sbb.tms.ssp.chat.config.properties.OAuth2ResourceServerConfig.JwtIssuerConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;

import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfigSso {
    private static final String ROLES_KEY = "roles";
    private static final String AUD_KEY = "aud";
    private static final String ROLE_PREFIX = "ROLE_";

    private final OAuth2ResourceServerConfig oAuth2Config;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        Map<String, AuthenticationManager> authenticationManagers = new HashMap<>();
        JwtIssuerAuthenticationManagerResolver authenticationManagerResolver = new JwtIssuerAuthenticationManagerResolver(authenticationManagers::get);
        oAuth2Config.getJwt()
                    .getIssuer()
                    .forEach(issuer -> addManager(authenticationManagers, issuer));

        http
                // CSRF protection is enabled by default. It can be disabled using
                .csrf(AbstractHttpConfigurer::disable)
                // @see <a
                // href="https://docs.spring.io/spring-security/site/docs/current/reference/htmlsingle/#csrf-when">When
                // to use CSRF protection</a>

                // CORS: by default Spring uses a bean with the name of corsConfigurationSource:
                // @see ch.sbb.esta.config.CorsConfig
                .cors(withDefaults())

                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(new Http403ForbiddenEntryPoint())
                )

                // for details about stateless authentication see e.g.
                // https://golb.hplar.ch/2019/05/stateless.html
                .sessionManagement(sessionManagement -> sessionManagement
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // @see <a
                // href="https://docs.spring.io/spring-security/site/docs/current/reference/htmlsingle/#jc-authorize-requests">Authorize
                // Requests</a>
                .authorizeHttpRequests(authorizeRequests -> authorizeRequests
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .requestMatchers("/*.html", "/*.js", "/*.css", "/*.ico", "/*.json", "/assets/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health")
                        .permitAll() // For open shift health check
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                        .permitAll() // For monitoring
                        .requestMatchers(HttpMethod.GET, "/")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/**")
                        .permitAll()
                        .requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated()
                )

                // @see <a href="https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html">OAuth 2.0 Resource Server</a>
                .oauth2ResourceServer(oauth2 -> oauth2.authenticationManagerResolver(authenticationManagerResolver));

        return http.build();
    }

    public void addManager(Map<String, AuthenticationManager> authenticationManagers, JwtIssuerConfig issuer) {
        JwtAuthenticationProvider authenticationProvider = new JwtAuthenticationProvider(JwtDecoders.fromOidcIssuerLocation(issuer.getUri()));
        authenticationProvider.setJwtAuthenticationConverter(getJwtAuthenticationConverter(issuer.getType(), issuer.getIdentifier()));
        authenticationManagers.put(issuer.getUri(), authenticationProvider::authenticate);
    }


    @SuppressWarnings("unchecked")
    private Converter<Jwt, AbstractAuthenticationToken> getJwtAuthenticationConverter(OAuth2ResourceServerConfig.JwtIssuerType type, String identifier) {
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();

        conv.setJwtGrantedAuthoritiesConverter(jwt -> {
            if (Objects.requireNonNull(type) == OAuth2ResourceServerConfig.JwtIssuerType.AZUREAD) {
                List<String> roles = (List<String>) jwt
                        .getClaims()
                        .get(ROLES_KEY);
                List<String> aud = (List<String>) jwt
                        .getClaims()
                        .get(AUD_KEY);
                if (roles == null || roles.isEmpty() ||
                    aud == null || aud.isEmpty() || !aud.contains(identifier)) {
                    return Collections.emptyList();
                }
                return roles.stream()
                            .map(role -> ROLE_PREFIX + role)
                            .map(SimpleGrantedAuthority::new)
                            .collect(Collectors.toList());
            }

            return Collections.emptyList();
        });
        return conv;
    }
}