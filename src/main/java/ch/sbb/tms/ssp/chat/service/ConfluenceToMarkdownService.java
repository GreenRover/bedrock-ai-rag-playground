package ch.sbb.tms.ssp.chat.service;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConfluenceToMarkdownService {
    public static final String ATTACHMENT_PREFIX = "[[ATTACHMENT:";
    public static final String ATTACHMENT_SUFFIX = "]]";

    public record MarkdownResult(String markdown, List<String> outboundLinks) {}

    private final ConfluenceClient confluenceClient;

    public MarkdownResult convertToMarkdown(String storageFormat, String pageId) {
        Document doc = Jsoup.parseBodyFragment(storageFormat);

        // 1. User Mentions: <ac:link><ri:user ri:account-id="..."/></ac:link>
        Elements userLinks = doc.select("ac|link:has(ri|user)");
        for (Element link : userLinks) {
            Element user = link.selectFirst("ri|user");
            if (user != null) {
                String accountId = user.attr("ri:account-id");
                String displayName = confluenceClient.getUserDisplayName(accountId);
                link.replaceWith(new TextNode("@" + displayName));
            }
        }

        // 2. User Profiles: <ac:structured-macro ac:name="profile"><ac:parameter ac:name="user"><ri:user ri:account-id="..."/></ac:parameter></ac:structured-macro>
        Elements profiles = doc.select("ac|structured-macro[ac:name=profile]");
        for (Element profile : profiles) {
            Element user = profile.selectFirst("ri|user");
            if (user != null) {
                String accountId = user.attr("ri:account-id");
                String displayName = confluenceClient.getUserDisplayName(accountId);
                profile.replaceWith(new TextNode("@" + displayName));
            }
        }

        // 3. Images: <ac:image>
        Elements images = doc.select("ac|image");
        for (Element image : images) {
            Element attachment = image.selectFirst("ri|attachment");
            if (attachment != null) {
                String filename = attachment.attr("ri:filename");
                image.replaceWith(new TextNode(ATTACHMENT_PREFIX + filename + ATTACHMENT_SUFFIX));
            }
        }

        // 4. Outbound Links: <ac:link><ri:page ri:content-title="Target Page Title"/></ac:link>
        Elements pageLinks = doc.select("ac|link:has(ri|page)");
        List<String> outboundLinks = pageLinks.stream()
                .map(link -> link.selectFirst("ri|page"))
                .filter(Objects::nonNull)
                .map(page -> page.attr("ri:content-title"))
                .filter(title -> !title.isEmpty())
                .distinct()
                .toList();

        // Convert the cleaned HTML to Markdown
        MutableDataSet options = new MutableDataSet();
        // You can add flexmark options here if needed

        String html = doc.body().html();
        String markdown = FlexmarkHtmlConverter.builder(options).build().convert(html).trim();

        return new MarkdownResult(markdown, outboundLinks);
    }
}
