package ch.sbb.greenrover.rag.controller;

import ch.sbb.greenrover.rag.service.Assistant;
import dev.langchain4j.service.Result;
import dev.langchain4j.rag.content.ContentMetadata;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ChatController {

    private final Assistant assistant;

    public record SourceDto(String text, Double score, String title) {}
    public record ChatResponseDto(String answer, List<SourceDto> sources) {}

    @PostMapping("/api/chat")
    @Operation(summary = "Ask a question using the RAG AI")
    public ChatResponseDto chat(@RequestBody String message) {
        Result<String> result = assistant.chat(message);
        List<SourceDto> sources = result.sources().stream()
                .map(content -> {
                    String text = content.textSegment().text();
                    Object scoreObj = content.metadata().get(ContentMetadata.SCORE);
                    Double score = scoreObj != null ? ((Number) scoreObj).doubleValue() : null;
                    String title = content.textSegment().metadata().getString("title");
                    return new SourceDto(text, score, title);
                })
                .toList();
        return new ChatResponseDto(result.content(), sources);
    }
}


