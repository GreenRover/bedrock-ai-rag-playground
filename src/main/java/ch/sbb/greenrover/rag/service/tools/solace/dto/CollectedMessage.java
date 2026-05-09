package ch.sbb.greenrover.rag.service.tools.solace.dto;

public record CollectedMessage(
        Long concentratorSrcLatency, Long concentratorDstLatency, Long brokerDstLatency, String teaser
) {}
