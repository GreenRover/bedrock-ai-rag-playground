package ch.sbb.tms.ssp.chat.config.spring;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

@Configuration
public class OpenApiConfig {
    private final String issuer;
    private final String scopes;
    private final boolean isBuild;

    public OpenApiConfig(
            @Value("${springdoc.oAuthFlow.issuer}") String issuer,
            @Value("${springdoc.swagger-ui.oauth.scopes[0]}") String scopes,
            Environment environment
    ) {
        this.issuer = issuer;
        this.scopes = scopes;
        isBuild = Arrays.asList(environment.getActiveProfiles())
                .contains("generate-api");
    }


    @Bean
    public OpenAPI openAPI() {
        OpenAPI openApi = new OpenAPI()
                .info(new Info()
                        .title("TMS-SSP Chat")
                        .contact(new Contact()
                                .name("TMS-Solace-Prod-Team")
                                .email("solace.prod@sbb.ch")
                                .url("https://confluence.sbb.ch/x/t4pTcQ")
                        )
                );

        if (!isBuild) {
            openApi.getInfo()
                    .description("Chat with our knowledge, ...");

            openApi
                    .addServersItem(new Server().url("/"))
                    .components(new Components()
                            .addSecuritySchemes("spring_oauth", new SecurityScheme()
                                    .type(SecurityScheme.Type.OAUTH2)
                                    .description("Oauth2 flow")
                                    .flows(new OAuthFlows()
                                            .authorizationCode(new OAuthFlow()
                                                    .authorizationUrl(issuer + "/authorize")
                                                    .refreshUrl(issuer + "/token")
                                                    .tokenUrl(issuer + "/token")
                                                    .scopes(new Scopes()
                                                            .addString(scopes, "Default scope for Azure AD API")
                                                    )
                                            )
                                    )
                            )
                    )
                    .security(Collections.singletonList(
                            new SecurityRequirement().addList("spring_oauth")
                    ));
        }

        return openApi;
    }

    @Bean
    public OpenApiCustomizer customerGlobalHeaderOpenApiCustomiser() {
        return openApi -> openApi.getPaths()
                .values()
                .forEach(pathItem -> pathItem.readOperations()
                        .forEach(operation -> {
                            operation.getResponses()
                                    .addApiResponse(
                                            "401",
                                            new ApiResponse()
                                                    .description("Valid oAuth token required")
                                                    .content(new Content()
                                                            .addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, new MediaType()))
                                    );
                            operation.getResponses()
                                    .addApiResponse(
                                            "403",
                                            new ApiResponse()
                                                    .description("Forbidden, missing requirements")
                                                    .content(new Content()
                                                            .addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, new MediaType()))
                                    );
                        }));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Bean
    public OpenApiCustomizer stableOrderOpenApiCustomiser() {
        return openApi -> {
            // Sort top-level paths
            if (openApi.getPaths() != null) {
                var sorted = new TreeMap<>(openApi.getPaths());
                openApi.getPaths().clear();
                sorted.forEach((k, v) -> openApi.getPaths().addPathItem(k, v));
            }

            // Sort component schemas and their properties
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                Map<String, Schema> sortedSchemas = new TreeMap<>(openApi.getComponents().getSchemas());
                sortedSchemas.values().forEach(schema -> {
                    if (schema.getProperties() != null) {
                        schema.setProperties(new TreeMap<>(schema.getProperties()));
                    }
                });
                openApi.getComponents().setSchemas(sortedSchemas);
            }
        };
    }

}
