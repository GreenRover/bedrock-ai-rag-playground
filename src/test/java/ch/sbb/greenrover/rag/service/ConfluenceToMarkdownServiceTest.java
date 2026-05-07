package ch.sbb.greenrover.rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfluenceToMarkdownServiceTest {

    @Mock
    private ConfluenceClient confluenceClient;

    private ConfluenceToMarkdownService service;

    @BeforeEach
    void setUp() {
        service = new ConfluenceToMarkdownService(confluenceClient);
    }

    @Test
    void shouldConvertToMarkdown() {
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
        ConfluenceToMarkdownService.MarkdownResult result = service.convertToMarkdown(input, "page-789");

        // Then
        String markdown = result.markdown();
        assertThat(markdown, containsString("Project Update"));
        assertThat(markdown, containsString("Hello @Alice Smith,"));
        assertThat(markdown, containsString("@Bob Jones"));
        assertThat(markdown, containsString("ATTACHMENT:architecture.png"));
        assertThat(markdown, containsString("`npm install confluence-api`"));
    }

    @Test
    void shouldExtractOutboundLinks() {
        // Given
        String input = """
                <div>
                    <p>Check the <ac:link><ri:page ri:content-title="Architecture Diagram"/></ac:link> for more info.</p>
                    <p>Also see <ac:link><ri:page ri:content-title="Setup Guide"/></ac:link>.</p>
                    <p>Duplicate link: <ac:link><ri:page ri:content-title="Architecture Diagram"/></ac:link></p>
                </div>
                """;

        // When
        ConfluenceToMarkdownService.MarkdownResult result = service.convertToMarkdown(input, "page-123");

        // Then
        assertThat(result.outboundLinks(), contains("Architecture Diagram", "Setup Guide"));
    }
}
