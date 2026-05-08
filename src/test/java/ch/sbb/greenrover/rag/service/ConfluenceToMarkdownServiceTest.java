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
    @Test
    void shouldExtractExpandMacroContent() {
        // Given
        String input = """
                <ac:structured-macro ac:macro-id="27bb4667-9667-4e14-9924-50d2fd939358" ac:name="iframe" ac:schema-version="1">
                  <ac:parameter ac:name="src">
                    <ri:url ri:value="https://sbb.sharepoint.com/sites/trs-team/_layouts/15/embed.aspx?UniqueId=43b36594-073c-4d45-bf33-b4b6451da260&amp;embed=%7B%22ust%22%3Afalse%2C%22hv%22%3A%22CopyEmbedCode%22%7D&amp;referrer=StreamWebApp&amp;referrerScenario=EmbedDialog"/>
                  </ac:parameter>
                  <ac:parameter ac:name="width">100%</ac:parameter>
                  <ac:parameter ac:name="height">500px</ac:parameter>
                  <ac:rich-text-body>
                    <p>
                      <br/>
                    </p>
                  </ac:rich-text-body>
                </ac:structured-macro>
                <ac:structured-macro ac:macro-id="5d623b85-e4d4-40c0-8162-2add57e394e9" ac:name="expand" ac:schema-version="1">
                  <ac:parameter ac:name="title">Video subtitle</ac:parameter>
                  <ac:rich-text-body>
                    <p>In this video you’ll learn how to create a broker and connect a Spring application to it.<br/>First, select your application context and click the plus icon.<br/>At the top, you’ll see a list of clusters. Choose the cluster that matches the OpenShift cluster where your application runs. If you’re not sure, please contact your system architect.<br/>Now choose a Stage for your application.<br/>The Stage Suffix is optional, but highly recommended. The combination of Stage and Stage Suffix must be unique. Without a suffix, you can only have a single broker per stage.<br/>Next, choose the broker variant. Read the descriptions carefully, estimate how many connections you’ll need, and decide whether you require a highly available Enterprise broker or if a Developer broker is sufficient.<br/>You can now configure LDAP groups. We recommend leaving this blank. The tms SSP will auto‑generate the groups, and users and groups from the application context permissions will automatically get access.<br/>Set the Storage size in GB. This is the second performance indicator for your broker. It limits how many messages can be stored, and because SBB runs on AWS, disk size also influences disk I/O performance. We show a conservative estimate of the broker’s possible message throughput.<br/>Choose a broker version. There are two main tracks: an LTS like branch that only receives hotfixes, and a rolling feature branch that also gets new features. This choice affects auto‑updates. On the LTS track, patches arrive roughly every two weeks for about a year; after that you’ll need to manually upgrade to the next release. On the rolling track, the system applies ongoing updates and keeps the latest features.<br/>Decide whether your broker must be reachable from the internet, or only from the SBB network.</p>
                    <p>At this point, decide whether to automate the setup or do it manually. I’ll show the manual approach first.<br/>The broker installation now starts. This usually takes 4 to 10 minutes.<br/>When the setup finishes, open the broker details.<br/>On the Broker info page, you’ll find all available information for this broker. You can see the generated group name starting with DG_RBT_tms SSP and a button to modify the LDAP groups.<br/>In the Active protocols section, you’ll see the list of supported protocols and the default hostnames. By default, those hostnames contain random characters.<br/>Let’s assign human‑friendly hostnames to the broker. Because you can set different hostnames for the SBB internal interface and the internet‑facing interface, you may need to assign two hostnames.<br/>Now the Active protocols section looks much clearer.<br/>To connect to the broker, you need credentials. SBB security supports two authentication methods: OAuth, and SSL client certificate authentication. For Java applications, SSL client certificate authentication is preferred. Let’s create a client certificate.<br/>A client certificate is valid for all brokers within the same stage and application context. For example, you can share one certificate across all of your development brokers.<br/>Enter a descriptive name for your microservice. Having an individual application user per Java microservice allows you to isolate services from each other and define per‑service default queue settings. We highly recommend creating one application user per microservice.<br/>If you have Windows Sub system for Linux installed, choose the Secure option. With the Easy option, your private key would be generated on our server. Secure ensures your private key is generated locally.<br/>Copy the command into your linux terminal. Your computer will generate two files: your private key, which you must never share, and a certificate signing request. Paste the CSR into the tms SSP.<br/>After clicking Add, you’ll find the generated certificate in the list, including its validity period and a download option.<br/>Treat this certificate like a password that is used with a ClientUsername matching the certificate’s FQDN. You still need to create that user on your broker—this is covered in another tutorial.<br/>You can now add the private key and certificate to your Spring application configuration so the provided Spring Boot starter can connect to your broker.</p>
                    <p>That was the manual way. Now let’s do the same with automation.<br/>Return to the broker creation form and choose Generate Operator Config. This is a great place to start if this is your first automation.<br/>Follow the steps to download a Helm chart that sets everything up for you.<br/>In Argo CD, here’s what the result looks like. First, the Broker custom resource. It contains all the options you selected in the broker creation form, including the broker name. The tms-broker-operator will create the broker via tms SSP for you.<br/>Next, you’ll need a ClientUserCertificate resource, which represents the application user in tms SSP. The operator will automatically renew the certificate for you.<br/>Pay attention to the secretName field. The operator creates an OpenShift Secret with this name for your Deployment, containing all the information needed to connect to the broker.<br/>Your Deployment then references this generated Secret.<br/>An even simpler example is available in the operator’s documentation.</p>
                  </ac:rich-text-body>
                </ac:structured-macro>
                <p>
                  <br/>
                </p>
                <p>
                  <br/>
                </p>
                <p>First thing to do is to open the TMS self-service portal for solace broker: <a href="https://tms-ssp.sbb-cloud.net/">https://tms-ssp.sbb-cloud.net/</a>
                </p>
                """;

        // When
        ConfluenceToMarkdownService.MarkdownResult result = service.convertToMarkdown(input, "page-123");

        // Then
        String markdown = result.markdown();
        assertThat(markdown, containsString("First thing to do is to open"));
        assertThat(markdown, containsString("Follow the steps to download a Helm chart"));
    }
}
