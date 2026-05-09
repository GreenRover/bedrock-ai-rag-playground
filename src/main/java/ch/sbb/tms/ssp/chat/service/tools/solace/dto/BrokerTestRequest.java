package ch.sbb.tms.ssp.chat.service.tools.solace.dto;

public record BrokerTestRequest(
        String brokerSrc, String brokerDst, String topic, boolean sendTestMessage, String timeout
) {
}
