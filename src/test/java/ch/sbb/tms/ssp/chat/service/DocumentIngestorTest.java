package ch.sbb.tms.ssp.chat.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class DocumentIngestorTest {

    @Test
    void testMarkdownDocumentSplitter() {
        // Arrange
        DocumentIngestor.MarkdownDocumentSplitter splitter = new DocumentIngestor.MarkdownDocumentSplitter(1000, 0);

        String markdown = "# Header 1\n" +
                "Some text under header 1.\n" +
                "## Header 2\n" +
                "Some text under header 2.\n" +
                "### Header 3\n" +
                "Some text under header 3.\n" +
                "# Another Header 1\n" +
                "More text.";

        Document document = Document.document(markdown, Metadata.metadata("source", "test.md"));

        // Act
        List<TextSegment> segments = splitter.split(document);

        // Assert
        assertThat(segments, hasSize(4));

        assertThat(segments.get(0).text(), containsString("# Header 1"));
        assertThat(segments.get(0).metadata().getString("parent_context"), is(nullValue()));

        assertThat(segments.get(1).text(), containsString("## Header 2"));
        assertThat(segments.get(1).metadata().getString("parent_context"), is("Header 1"));

        assertThat(segments.get(2).text(), containsString("### Header 3"));
        assertThat(segments.get(2).metadata().getString("parent_context"), is("Header 1 > Header 2"));

        assertThat(segments.get(3).text(), containsString("# Another Header 1"));
        assertThat(segments.get(3).metadata().getString("parent_context"), is(nullValue()));
    }
}
