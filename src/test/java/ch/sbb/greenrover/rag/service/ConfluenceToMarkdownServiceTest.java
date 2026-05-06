package ch.sbb.greenrover.rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ConfluenceToMarkdownServiceTest {

    @Mock
    private ConfluenceClient confluenceClient;

    private ConfluenceToMarkdownService service;

    @BeforeEach
    void setUp() {
        service = new ConfluenceToMarkdownService(confluenceClient);
    }

    @Test
    void testConvertToMarkdown() {
        // Given
        String input = """
                <div>
                    <h2>Project Update</h2>
                    <p>Hello <ac:link><ri:user ri:account-id="user-123"/></ac:link>,</p>
                    <p>Please contact the project lead:</p>
                    <ac:structured-macro ac:name="profile">
                        <ac:parameter ac:name="user">
                            <ri:user ri:account-id="user-456"/>
                        </ac:parameter>
                    </ac:structured-macro>
                    <p>Here is the architecture diagram:</p>
                    <ac:image ac:width="500">
                        <ri:attachment ri:filename="architecture.png"/>
                    </ac:image>
                    <p>Run the following command:</p>
                    <p><code>npm install confluence-api</code></p>
                </div>
                """;

        when(confluenceClient.getUserDisplayName("user-123")).thenReturn("Alice Smith");
        when(confluenceClient.getUserDisplayName("user-456")).thenReturn("Bob Jones");

        // When
        String markdown = service.convertToMarkdown(input, "page-789");

        // Then
        System.out.println("[DEBUG_LOG] Generated Markdown:\n" + markdown);

        assertTrue(markdown.contains("Project Update"), "Should contain Project Update");
        assertTrue(markdown.contains("Hello @Alice Smith,"), "Should contain Hello @Alice Smith,");
        assertTrue(markdown.contains("@Bob Jones"), "Should contain @Bob Jones");
        assertTrue(markdown.contains("[[ATTACHMENT:architecture.png]]") || markdown.contains("\\[\\[ATTACHMENT:architecture.png\\]\\]"), "Should contain attachment placeholder (possibly escaped)");
        assertTrue(markdown.contains("`npm install confluence-api`"), "Should contain code block");
    }
}
