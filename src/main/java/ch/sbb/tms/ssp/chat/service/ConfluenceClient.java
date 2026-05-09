package ch.sbb.tms.ssp.chat.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;

public interface ConfluenceClient {
    String getUserDisplayName(String accountId);

    JsonNode getApiResult(String path);

    void downloadAttachment(String downloadUrl, Path targetFile);

    String getBaseUrl();
}
