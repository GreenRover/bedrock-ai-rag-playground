package ch.sbb.greenrover.rag.service.tools.solace.dto;

import java.util.List;

public record BrokerTestResult(
        String brokerSrc, String brokerDst, String concentratorSrc, String concentratorDst,
        boolean containsTeaser, boolean testMsgSend, String topic, List<CollectedMessage> messages
) {
}
