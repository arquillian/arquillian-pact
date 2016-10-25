package org.arquillian.pact.consumer.core.publisher;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.matching.ContainsPattern;
import com.github.tomakehurst.wiremock.matching.EqualToPattern;
import org.arquillian.pact.consumer.core.PactConsumerConfiguration;
import org.arquillian.pact.consumer.core.PactReportDirectoryConfigurator;
import org.arquillian.pact.consumer.core.PublishPacts;
import org.jboss.arquillian.core.api.annotation.ApplicationScoped;
import org.jboss.arquillian.core.test.AbstractManagerTestBase;
import org.jboss.arquillian.test.spi.event.suite.AfterSuite;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

public class UrlPublishPactsTest extends AbstractManagerTestBase {

    @Override
    protected void addExtensions(List<Class<?>> extensions) {
        extensions.add(PublishPacts.class);
        super.addExtensions(extensions);
    }

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Rule
    public WireMockRule service = new WireMockRule(8081);

    @Test
    public void should_move_files_to_output_directory() throws IOException {

        try {
            final File origin = temporaryFolder.newFolder("origin");
            System.setProperty(PactReportDirectoryConfigurator.PACT_ROOT_DIR, origin.getAbsolutePath());

            byte[] content = "Contract File".getBytes();
            Files.copy(new ByteArrayInputStream(content), new File(origin, "pact.txt").toPath());

            String config = "provider: url\n" +
                    "url: http://localhost:8081/pacts\n";

            final Map<String, String> params = new HashMap<>();
            params.put("publishContracts", "true");
            params.put("pactPublishConfiguration", config);

            final PactConsumerConfiguration pactConsumerConfiguration = PactConsumerConfiguration.fromMap(params);
            bind(ApplicationScoped.class, PactConsumerConfiguration.class, pactConsumerConfiguration);

            stubFor(
                    post(urlEqualTo("/pacts/pact.txt"))
                        .withRequestBody(new ContainsPattern("Contract File"))
                        .willReturn(aResponse().withStatus(200)));

            fire(new AfterSuite());

            verify(postRequestedFor(urlEqualTo("/pacts/pact.txt"))
                    .withRequestBody(new ContainsPattern("Contract File")));


        } finally {
            System.clearProperty(PactReportDirectoryConfigurator.PACT_ROOT_DIR);
        }
    }

}
