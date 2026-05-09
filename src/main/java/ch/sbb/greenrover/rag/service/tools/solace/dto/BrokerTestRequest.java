package ch.sbb.greenrover.rag.service.tools.solace.dto;

public record BrokerTestRequest(
        String brokerSrc, String brokerDst, String topic, boolean sendTestMessage, String timeout
) {
}
