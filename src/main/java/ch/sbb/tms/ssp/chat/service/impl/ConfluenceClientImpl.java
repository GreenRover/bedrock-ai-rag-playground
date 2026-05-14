package ch.sbb.tms.ssp.chat.service.impl;

import ch.sbb.tms.ssp.chat.config.properties.ConfluenceProperties;
import ch.sbb.tms.ssp.chat.service.ConfluenceClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

@Service
@Slf4j
public class ConfluenceClientImpl implements ConfluenceClient {

    private static final String CONFLUENCE_USER_API_PATH = "/wiki/rest/api/user?accountId=";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String APPLICATION_JSON = "application/json";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiToken;

    public ConfluenceClientImpl(
            ConfluenceProperties confluenceProperties
    ) {

        this.baseUrl = confluenceProperties.getBaseUrl();
        this.apiToken = confluenceProperties.getToken();

        Assert.notNull(baseUrl, "Confluence baseUrl must not be null");
        Assert.notNull(apiToken, "Confluence apiToken must not be null");

        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    @Cacheable(value = "confluenceUsers", key = "#accountId", unless = "#result == null")
    public String getUserDisplayName(String accountId) {
        log.debug("Fetching user details for accountId: {}", accountId);
        try {
            JsonNode jsonNode = getApiResult(CONFLUENCE_USER_API_PATH + accountId);
            if (jsonNode != null && jsonNode.has("displayName")) {
                return jsonNode.get("displayName").asText();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch user display name for account {}: {}", accountId, e.getMessage());
        }

        // Fallback if the user cannot be found
        return "Unknown User (" + accountId + ")";
    }

    @Override
    public JsonNode getApiResult(String path) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Add a small delay between requests to avoid DDoS
                // Vai trial and error to not trigger confluence rate limit
                Thread.sleep(150L);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
                        .header(ACCEPT_HEADER, APPLICATION_JSON)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    if (attempt < maxRetries) {
                        log.warn("Rate limit exceeded (429) from Confluence API for path {}. Cooling down before retry {}/{}", path, attempt, maxRetries);
                        Thread.sleep(10_000L * attempt); // exponential backoff
                        continue;
                    } else {
                        throw new RuntimeException("Failed API Call: " + response.statusCode() + " - " + response.body());
                    }
                }

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Failed API Call: " + response.statusCode() + " - " + response.body());
                }

                // Defensive check: Ensure we actually got JSON back
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.contains("application/json")) {
                    log.error("Expected JSON but received Content-Type: {} for page: {}. Body starts with: \n{}",
                            contentType,
                            path,
                            response.body().substring(0, Math.min(200, response.body().length())));
                    throw new RuntimeException("API did not return JSON. Likely redirected to an HTML login page.");
                }

                return objectMapper.readTree(response.body());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted", ie);
            } catch (Exception e) {
                if (attempt == maxRetries || e instanceof RuntimeException && !e.getMessage().contains("429")) {
                    throw new RuntimeException("Error calling Confluence API: " + e.getMessage(), e);
                }
            }
        }
        throw new RuntimeException("Max retries exceeded for Confluence API");
    }

    @Override
    public void downloadAttachment(String downloadUrl, Path targetFile) {
        try {
            String fullUrl = downloadUrl.startsWith("http") ? downloadUrl :
                    baseUrl.replaceAll("/wiki/?$", "") + downloadUrl;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
                    .GET()
                    .build();

            int maxRetries = 3;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    // Add a small delay between requests to avoid DDoS
                    Thread.sleep(500);

                    HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(targetFile));

                    if (response.statusCode() == 429) {
                        if (attempt < maxRetries) {
                            log.warn("Rate limit exceeded (429) from Confluence API for attachment {}. Cooling down before retry {}/{}", downloadUrl, attempt, maxRetries);
                            Thread.sleep(5000L * attempt); // exponential backoff
                            continue;
                        } else {
                            throw new RuntimeException("Failed to download attachment " + downloadUrl + " (Status: " + response.statusCode() + ")");
                        }
                    }

                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Failed to download attachment " + downloadUrl + " (Status: " + response.statusCode() + ")");
                    }
                    return; // Success
                } catch (java.io.IOException e) {
                    if (e.getMessage() != null && e.getMessage().contains("GOAWAY") && attempt < maxRetries) {
                        log.warn("Received GOAWAY from Confluence for {}. Cooling down before retry {}/{}", downloadUrl, attempt, maxRetries);
                        try {
                            Thread.sleep(5000); // 5 seconds cool down
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Thread interrupted during cool down", ie);
                        }
                    } else {
                        throw e; // Rethrow if it's not GOAWAY or max retries reached
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted", ie);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error downloading attachment: " + e.getMessage(), e);
        }
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }
}
