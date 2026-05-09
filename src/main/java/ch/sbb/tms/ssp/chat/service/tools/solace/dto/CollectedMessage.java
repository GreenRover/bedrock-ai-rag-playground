package ch.sbb.tms.ssp.chat.service.tools.solace.dto;

public record CollectedMessage(
        Long concentratorSrcLatency, Long concentratorDstLatency, Long brokerDstLatency, String teaser
) {}
