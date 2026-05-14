package ch.sbb.tms.ssp.chat.service.tools;

import ch.sbb.tms.ssp.chat.config.properties.RagProperties;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("unused")
public class DocumentRetrievalTools {

    private final RagProperties ragProperties;

    @Tool("Fetches the full Markdown content of a documentation page by its title. Use this when a user asks about a topic or you see a relevant link in outbound_links, but you don't have enough context.")
    public String fetchDocumentContent(String title) {
        String safeTitle = title.replaceAll("[\\\\/*?:\"<>|]", "").replace(" ", "_");
        String fileSuffix = "_" + safeTitle + ".md";
        Path exportPath = Paths.get(ragProperties.getData().getExportDir());

        if (!Files.exists(exportPath) || !Files.isDirectory(exportPath)) {
            return "Error: Export directory not found.";
        }

        try (Stream<Path> stream = Files.list(exportPath)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(fileSuffix))
                    .findFirst()
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (IOException e) {
                            log.error("Failed to read file: {}", path, e);
                            return "Error: Failed to read the file content.";
                        }
                    })
                    .orElse("Error: Could not find a documentation file for title: " + title);
        } catch (IOException e) {
            log.error("Failed to list export directory", e);
            return "Error: Failed to access the documentation directory.";
        }
    }
}

