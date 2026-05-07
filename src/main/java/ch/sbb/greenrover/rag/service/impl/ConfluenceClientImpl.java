package ch.sbb.greenrover.rag.service.impl;

import ch.sbb.greenrover.rag.service.ConfluenceClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

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
            @Value("${confluence.api.url}") String baseUrl,
            @Value("${confluence.api.token}") String apiToken) {

        this.baseUrl = baseUrl;
        this.apiToken = apiToken;
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
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
                    .header(ACCEPT_HEADER, APPLICATION_JSON)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed API Call: " + response.statusCode() + " - " + response.body());
            }

            // Defensive check: Ensure we actually got JSON back
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.contains("application/json")) {
                log.error("Expected JSON but received Content-Type: {}. Body starts with: \n{}",
                        contentType,
                        response.body().substring(0, Math.min(200, response.body().length())));
                throw new RuntimeException("API did not return JSON. Likely redirected to an HTML login page.");
            }

            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new RuntimeException("Error calling Confluence API: " + e.getMessage(), e);
        }
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

            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(targetFile));
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to download attachment " + downloadUrl + " (Status: " + response.statusCode() + ")");
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
