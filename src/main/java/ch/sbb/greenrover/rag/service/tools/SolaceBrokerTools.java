package ch.sbb.greenrover.rag.service.tools;

import ch.sbb.greenrover.rag.service.tools.solace.SolaceBackendFacade;
import ch.sbb.greenrover.rag.service.tools.solace.dto.BrokerPublicInfo;
import ch.sbb.greenrover.rag.service.tools.solace.dto.BrokerTestRequest;
import ch.sbb.greenrover.rag.service.tools.solace.dto.BrokerTestResult;
import ch.sbb.greenrover.rag.service.tools.solace.dto.CollectedMessage;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("unused") // Methods are invoked reflectively by the LLM agent, not directly in code
public class SolaceBrokerTools {

    private final SolaceBackendFacade solaceFacade;

    @Tool("Provides a list of all existing Solace broker names and their UUIDs. Use this to find the correct UUIDs needed for the analyzeDataflow tool.")
    public String listExistingBrokers() {
        log.info("AI Agent triggered tool: listExistingBrokers");
        try {
            List<BrokerPublicInfo> brokers = solaceFacade.getPublicBrokers();

            if (brokers == null || brokers.isEmpty()) {
                return "No brokers found in the environment.";
            }

            return brokers.stream()
                    .map(b -> String.format("- %s (UUID: %s)", formatBrokerName(b), b.brokerUuid()))
                    .collect(Collectors.joining("\n"));

        } catch (Exception e) {
            log.error("Tool execution failed: listExistingBrokers", e);
            return "Error fetching broker list: " + e.getMessage();
        }
    }

    @Tool("Analyzes the dataflow between a source broker and a destination broker for a specific topic. You MUST provide the UUIDs of the brokers, not their names.")
    public String analyzeDataflow(
            @P("The UUID of the source broker") String sourceBrokerUuid,
            @P("The UUID of the destination broker") String destinationBrokerUuid,
            @P("The message topic to analyze (e.g., tms/app/domain/DEV/...)") String topic
    ) {

        log.info("AI Agent triggered tool: analyzeDataflow(src={}, dst={}, topic={})", sourceBrokerUuid, destinationBrokerUuid, topic);
        try {
            BrokerTestRequest requestPayload = new BrokerTestRequest(
                    sourceBrokerUuid,
                    destinationBrokerUuid,
                    topic,
                    true, // sendTestMessage
                    "PT20S" // timeout
            );

            BrokerTestResult result = solaceFacade.testConcentratorConnection(requestPayload);

            if (result == null) {
                return "Error: Received empty response from the data flow test API.";
            }

            return formatTestResult(result);

        } catch (Exception e) {
            log.error("Tool execution failed: analyzeDataflow", e);
            return "Error analyzing dataflow: " + e.getMessage();
        }
    }

    // --- Formatters to prepare data for the LLM ---

    private String formatBrokerName(BrokerPublicInfo b) {
        String stageSuffix = (b.stageSuffix() != null && !b.stageSuffix().isBlank()) ? "-" + b.stageSuffix() : "";
        return String.format("%s/%s/%s/%s%s",
                b.tmsAbbl1(), b.tmsAbbl2(), b.tmsAbbl3(), b.stage().toLowerCase(), stageSuffix);
    }

    private String formatTestResult(BrokerTestResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dataflow Analysis Result:\n");
        sb.append("- Topic: ").append(result.topic()).append("\n");
        sb.append("- Source Concentrator: ").append(result.concentratorSrc()).append("\n");
        sb.append("- Destination Concentrator: ").append(result.concentratorDst()).append("\n");

        if (result.messages() == null || result.messages().isEmpty()) {
            sb.append("\nSTATUS: FAILED.\n");
            sb.append("No messages were received. There might be a missing ACL, missing subscription, or network issue between the brokers.");
        } else {
            sb.append("\nSTATUS: SUCCESS.\n");
            sb.append(String.format("Successfully transmitted %d test messages.\n", result.messages().size()));
            sb.append("Latest message latencies:\n");

            CollectedMessage msg = result.messages().getFirst();
            sb.append(String.format("  - Src Concentrator Latency: %s\n", formatLatency(msg.concentratorSrcLatency())));
            if (!result.concentratorSrc().equals(result.concentratorDst())) {
                sb.append(String.format("  - Dst Concentrator Latency: %s\n", formatLatency(msg.concentratorDstLatency())));
            }
            sb.append(String.format("  - Dst Broker Latency: %s\n", formatLatency(msg.brokerDstLatency())));
        }

        return sb.toString();
    }

    private String formatLatency(Long latency) {
        return latency == null ? "Not Received" : latency + "ms";
    }
}