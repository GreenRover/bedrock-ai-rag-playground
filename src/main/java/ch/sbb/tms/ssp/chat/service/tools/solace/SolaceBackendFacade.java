package ch.sbb.tms.ssp.chat.service.tools.solace;

import ch.sbb.tms.ssp.chat.service.tools.solace.dto.BrokerPublicInfo;
import ch.sbb.tms.ssp.chat.service.tools.solace.dto.BrokerTestRequest;
import ch.sbb.tms.ssp.chat.service.tools.solace.dto.BrokerTestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SolaceBackendFacade {
    private final RestClient solaceBackendRestClient;

    public List<BrokerPublicInfo> getPublicBrokers() {
        log.debug("Fetching public brokers from Solace backend");
        return solaceBackendRestClient.get()
                .uri("/brokers/public")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    public BrokerTestResult testConcentratorConnection(BrokerTestRequest requestPayload) {
        log.debug("Testing concentrator connection for topic: {}", requestPayload.topic());
        return solaceBackendRestClient.post()
                .uri("/test-concentrator-connection")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(requestPayload)
                .retrieve()
                .body(BrokerTestResult.class);
    }
}
