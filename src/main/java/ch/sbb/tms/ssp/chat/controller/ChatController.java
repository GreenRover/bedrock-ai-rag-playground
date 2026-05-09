package ch.sbb.tms.ssp.chat.controller;

import ch.sbb.tms.ssp.chat.service.Assistant;
import dev.langchain4j.service.Result;
import dev.langchain4j.rag.content.ContentMetadata;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final Assistant assistant;

    public record SourceDto(String text, Double score, String title) {}
    public record ChatRequestDto(String message, String systemPromptContext) {}
    public record ChatResponseDto(String answer, List<SourceDto> sources) {}

    @PostMapping("/api/chat")
    @Operation(summary = "Ask a question using the RAG AI")
    public ChatResponseDto chat(@RequestBody ChatRequestDto request, HttpSession session) {
        log.info("Received prompt: {} with system context: {} for session: {}", request.message(), request.systemPromptContext(), session.getId());

        // Default to empty string if null to prevent prompt template errors
        String sysContext = request.systemPromptContext() != null ? request.systemPromptContext() : "";

        Result<String> result = assistant.chat(session.getId(), sysContext, request.message());

        List<SourceDto> sources = result.sources().stream()
                .map(content -> {
                    String text = content.textSegment().text();
                    Object scoreObj = content.metadata().get(ContentMetadata.SCORE);
                    Double score = scoreObj != null ? ((Number) scoreObj).doubleValue() : null;
                    String title = content.textSegment().metadata().getString("title");
                    return new SourceDto(text, score, title);
                })
                .toList();

        log.info("Response for prompt '{}': {}\n\n\tRagSources: {}", request.message(), result.content(), sources.size());

        return new ChatResponseDto(result.content(), sources);
    }
}
