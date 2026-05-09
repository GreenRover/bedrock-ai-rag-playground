package ch.sbb.greenrover.rag.service.tools.solace;

import ch.sbb.greenrover.rag.service.tools.solace.dto.BrokerPublicInfo;
import ch.sbb.greenrover.rag.service.tools.solace.dto.BrokerTestRequest;
import ch.sbb.greenrover.rag.service.tools.solace.dto.BrokerTestResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Service
public class SolaceBackendFacade {
    private final RestClient restClient;

    public SolaceBackendFacade(
            RestClient.Builder restClientBuilder,
            @Value("${solace.api.url:http://localhost:56242}") String solaceApiUrl) {
        this.restClient = restClientBuilder.baseUrl(solaceApiUrl).build();
    }

    public List<BrokerPublicInfo> getPublicBrokers() {
        log.debug("Fetching public brokers from Solace backend");
        return restClient.get()
                .uri("/brokers/public")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    public BrokerTestResult testConcentratorConnection(BrokerTestRequest requestPayload) {
        log.debug("Testing concentrator connection for topic: {}", requestPayload.topic());
        return restClient.post()
                .uri("/test-concentrator-connection")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(requestPayload)
                .retrieve()
                .body(BrokerTestResult.class);
    }
}
