package ch.sbb.greenrover.rag.scraper;

import ch.sbb.greenrover.rag.config.GithubProperties;
import ch.sbb.greenrover.rag.service.BedrockMediaTranslationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kohsuke.github.*;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GithubMarkdownScraperTest {

    @TempDir
    Path tempDir;

    private BedrockMediaTranslationService translationService;
    private GithubProperties githubProperties;
    private GitHub gitHub;
    private RestClient restClient;
    private RetryTemplate retryTemplate;
    private GithubMarkdownScraper scraper;

    @BeforeEach
    void setUp() {
        translationService = mock(BedrockMediaTranslationService.class);
        githubProperties = new GithubProperties();
        githubProperties.setExportDir(tempDir.toString());
        gitHub = mock(GitHub.class);

        restClient = mock(RestClient.class);
        retryTemplate = new RetryTemplate();

        scraper = new GithubMarkdownScraper(translationService, githubProperties, gitHub, restClient, retryTemplate);
    }

    @Test
    void testScrapeRepositoryWithImages() throws Exception {
        githubProperties.setToken("fake-token");
        githubProperties.setRepositories(List.of("https://github.com/owner/repo"));

        GHRepository repository = mock(GHRepository.class);
        when(gitHub.getRepository("owner/repo")).thenReturn(repository);
        when(repository.getDefaultBranch()).thenReturn("main");

        GHTree tree = mock(GHTree.class);
        GHTreeEntry markdownEntry = mock(GHTreeEntry.class);
        when(markdownEntry.getType()).thenReturn("blob");
        when(markdownEntry.getPath()).thenReturn("docs/test.md");
        when(tree.getTree()).thenReturn(List.of(markdownEntry));
        when(repository.getTreeRecursive("main", 1)).thenReturn(tree);

        GHContent mdContent = mock(GHContent.class);
        String mdText = "Here is an image: ![Alt Text](../images/test.png)\nAnd a random relative one: ![](local.jpg)";
        when(mdContent.read()).thenReturn(new ByteArrayInputStream(mdText.getBytes(StandardCharsets.UTF_8)));
        when(mdContent.getHtmlUrl()).thenReturn("https://github.com/owner/repo/blob/main/docs/test.md");
        when(repository.getFileContent("docs/test.md")).thenReturn(mdContent);

        GHContent imageContent1 = mock(GHContent.class);
        when(imageContent1.isFile()).thenReturn(true);
        when(imageContent1.read()).thenReturn(new ByteArrayInputStream("fake-png".getBytes(StandardCharsets.UTF_8)));
        when(repository.getFileContent("images/test.png")).thenReturn(imageContent1);

        GHContent imageContent2 = mock(GHContent.class);
        when(imageContent2.isFile()).thenReturn(true);
        when(imageContent2.read()).thenReturn(new ByteArrayInputStream("fake-jpg".getBytes(StandardCharsets.UTF_8)));
        when(repository.getFileContent("docs/local.jpg")).thenReturn(imageContent2);

        scraper.scrape();

        Path savedMd = tempDir.resolve("gh-owner-repo_docs_test.md");
        assertThat(savedMd).exists();
        String savedText = Files.readString(savedMd);
        assertThat(savedText).contains("title: 'test.md'");
        assertThat(savedText).contains("[[ATTACHMENT:images_test.png]]");
        assertThat(savedText).contains("[[ATTACHMENT:docs_local.jpg]]");

        Path savedImg1 = tempDir.resolve("assets").resolve("gh-owner-repo").resolve("images_test.png");
        assertThat(savedImg1).exists();
        assertThat(Files.readString(savedImg1)).isEqualTo("fake-png");

        Path savedImg2 = tempDir.resolve("assets").resolve("gh-owner-repo").resolve("docs_local.jpg");
        assertThat(savedImg2).exists();
        assertThat(Files.readString(savedImg2)).isEqualTo("fake-jpg");
    }

    @Test
    void testScrapeRepositoryWithExternalImages() throws Exception {
        githubProperties.setToken("fake-token");
        githubProperties.setRepositories(List.of("https://github.com/owner/repo"));

        GHRepository repository = mock(GHRepository.class);
        when(gitHub.getRepository("owner/repo")).thenReturn(repository);
        when(repository.getDefaultBranch()).thenReturn("main");

        GHTree tree = mock(GHTree.class);
        GHTreeEntry markdownEntry = mock(GHTreeEntry.class);
        when(markdownEntry.getType()).thenReturn("blob");
        when(markdownEntry.getPath()).thenReturn("docs/external.md");
        when(tree.getTree()).thenReturn(List.of(markdownEntry));
        when(repository.getTreeRecursive("main", 1)).thenReturn(tree);

        GHContent mdContent = mock(GHContent.class);
        String mdText = "External img: ![Ext](https://example.com/image.png)";
        when(mdContent.read()).thenReturn(new ByteArrayInputStream(mdText.getBytes(StandardCharsets.UTF_8)));
        when(mdContent.getHtmlUrl()).thenReturn("https://github.com/owner/repo/blob/main/docs/external.md");
        when(repository.getFileContent("docs/external.md")).thenReturn(mdContent);

        RestClient.RequestHeadersUriSpec getMock = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec uriMock = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec retrieveMock = mock(RestClient.ResponseSpec.class);
        ResponseEntity<byte[]> responseEntity = ResponseEntity.ok("fake-ext".getBytes(StandardCharsets.UTF_8));

        when(restClient.get()).thenReturn(getMock);
        when(getMock.uri(anyString())).thenReturn(uriMock);
        when(uriMock.retrieve()).thenReturn(retrieveMock);
        when(retrieveMock.toEntity(byte[].class)).thenReturn(responseEntity);

        scraper.scrape();

        Path savedMd = tempDir.resolve("gh-owner-repo_docs_external.md");
        assertThat(savedMd).exists();
        String savedText = Files.readString(savedMd);
        assertThat(savedText).contains("[[ATTACHMENT:image.png]]");

        Path savedExtImg = tempDir.resolve("assets").resolve("gh-owner-repo").resolve("image.png");
        assertThat(savedExtImg).exists();
        assertThat(Files.readString(savedExtImg)).isEqualTo("fake-ext");
    }

    @Test
    void testResolveRelativePath() {
        String result = ReflectionTestUtils.invokeMethod(scraper, "resolveRelativePath", "docs/guides/test.md", "../images/pic.png");
        assertThat(result).isEqualTo("docs/images/pic.png");

        result = ReflectionTestUtils.invokeMethod(scraper, "resolveRelativePath", "docs/test.md", "pic.png");
        assertThat(result).isEqualTo("docs/pic.png");

        result = ReflectionTestUtils.invokeMethod(scraper, "resolveRelativePath", "test.md", "pic.png");
        assertThat(result).isEqualTo("pic.png");

        result = ReflectionTestUtils.invokeMethod(scraper, "resolveRelativePath", "docs/test.md", "/absolute/pic.png");
        assertThat(result).isEqualTo("absolute/pic.png");

        result = ReflectionTestUtils.invokeMethod(scraper, "resolveRelativePath", "docs/test.md", "./pic.png");
        assertThat(result).isEqualTo("docs/pic.png");
    }
}
