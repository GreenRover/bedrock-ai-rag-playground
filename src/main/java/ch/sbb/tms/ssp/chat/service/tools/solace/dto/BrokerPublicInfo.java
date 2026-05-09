package ch.sbb.tms.ssp.chat.service.tools.solace.dto;

import java.util.List;

public record BrokerPublicInfo(
        String brokerUuid, String applicationUuid, String stage, String stageSuffix,
        String tmsAbbl1, String tmsAbbl2, String tmsAbbl3, List<String> additionalTopicPrefix
) {
}