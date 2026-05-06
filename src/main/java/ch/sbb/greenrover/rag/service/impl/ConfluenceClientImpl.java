package ch.sbb.greenrover.rag.service.impl;

import ch.sbb.greenrover.rag.service.ConfluenceClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class ConfluenceClientImpl implements ConfluenceClient {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceClientImpl.class);
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
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + CONFLUENCE_USER_API_PATH + accountId))
                    .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
                    .header(ACCEPT_HEADER, APPLICATION_JSON)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode jsonNode = objectMapper.readTree(response.body());
                if (jsonNode != null && jsonNode.has("displayName")) {
                    return jsonNode.get("displayName").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch user display name for account {}: {}", accountId, e.getMessage());
        }

        // Fallback if the user cannot be found
        return "Unknown User (" + accountId + ")";
    }
}
