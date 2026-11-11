package dev.airgap.bundler;

import com.sun.net.httpserver.HttpServer;
import dev.airgap.bundler.bundler.ScriptGenerator;
import dev.airgap.bundler.bundler.ZipStreamService;
import dev.airgap.bundler.config.AppConfig;
import dev.airgap.bundler.model.ArtifactRole;
import dev.airgap.bundler.model.BundleLayout;
import dev.airgap.bundler.model.BundleStreamRequest;
import dev.airgap.bundler.model.ResolveRequest;
import dev.airgap.bundler.model.ResolveResponse;
import dev.airgap.bundler.parser.DependencyParser;
import dev.airgap.bundler.resolver.MavenResolverService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class EndToEndResolverAndBundlerTest {

    private HttpServer mockCentral;
    private String mockCentralUrl;
    private final Map<String, byte[]> repoFiles = new HashMap<>();

    @TempDir
    Path tempCacheDir;

    @BeforeEach
    void setUp() throws Exception {
        // Set up a mock Maven Central with:
        // 1. Parent POM: com.acme:acme-parent:1.0.0
        // 2. Imported BOM POM: com.acme:acme-bom:1.0.0
        // 3. Transitive library: com.acme:acme-core:1.0.0 (.pom, .jar, -sources.jar)
        // 4. Direct library: com.acme:acme-web:1.0.0 (.pom, .jar) -> inherits acme-parent, imports acme-bom, depends on acme-core

        putMockPom("/com/acme/acme-parent/1.0.0/acme-parent-1.0.0.pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <bom.version>1.0.0</bom.version>
                  </properties>
                </project>
                """);

        putMockPom("/com/acme/acme-bom/1.0.0/acme-bom-1.0.0.pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-bom</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.acme</groupId>
                        <artifactId>acme-core</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        putMockPom("/com/acme/acme-core/1.0.0/acme-core-1.0.0.pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-core</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        repoFiles.put("/com/acme/acme-core/1.0.0/acme-core-1.0.0.jar", "FAKE_CORE_JAR_BYTES".getBytes(StandardCharsets.UTF_8));
        repoFiles.put("/com/acme/acme-core/1.0.0/acme-core-1.0.0-sources.jar", "FAKE_CORE_SOURCES".getBytes(StandardCharsets.UTF_8));

        putMockPom("/com/acme/acme-web/1.0.0/acme-web-1.0.0.pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>acme-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>acme-web</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.acme</groupId>
                        <artifactId>acme-bom</artifactId>
                        <version>${bom.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.acme</groupId>
                      <artifactId>acme-core</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        repoFiles.put("/com/acme/acme-web/1.0.0/acme-web-1.0.0.jar", "FAKE_WEB_JAR_BYTES".getBytes(StandardCharsets.UTF_8));

        mockCentral = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockCentral.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] data = repoFiles.get(path);
            if (data != null) {
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        mockCentral.start();
        mockCentralUrl = "http://127.0.0.1:" + mockCentral.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (mockCentral != null) {
            mockCentral.stop(0);
        }
    }

    private void putMockPom(String path, String xml) {
        repoFiles.put(path, xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void resolvesTransitiveGraphParentPomAndBomAndStreamsBothM2AndFlatZips() throws Exception {
        AppConfig config = new AppConfig(
                0, mockCentralUrl, tempCacheDir, 50, 500, 5, 100, Duration.ofSeconds(5), Duration.ofSeconds(5)
        );
        DependencyParser parser = new DependencyParser();
        MavenResolverService resolver = new MavenResolverService(config, parser);
        ScriptGenerator scriptGenerator = new ScriptGenerator();
        ZipStreamService zipStreamer = new ZipStreamService(config, resolver, scriptGenerator);

        // Step 1: Resolve com.acme:acme-web:1.0.0
        ResolveResponse resolved = resolver.resolve(new ResolveRequest(
                "com.acme:acme-web:1.0.0", true, true, true, List.of("compile", "runtime")
        ));

        // Expect 4 items: acme-web (DIRECT), acme-core (TRANSITIVE), acme-parent (PARENT_POM), acme-bom (BOM_POM)
        assertEquals(4, resolved.artifacts().size());
        assertTrue(resolved.artifacts().stream().anyMatch(a -> a.artifactId().equals("acme-web") && a.role() == ArtifactRole.DIRECT));
        assertTrue(resolved.artifacts().stream().anyMatch(a -> a.artifactId().equals("acme-core") && a.role() == ArtifactRole.TRANSITIVE));
        assertTrue(resolved.artifacts().stream().anyMatch(a -> a.artifactId().equals("acme-parent") && a.role() == ArtifactRole.PARENT_POM && a.pomOnly()));
        assertTrue(resolved.artifacts().stream().anyMatch(a -> a.artifactId().equals("acme-bom") && a.role() == ArtifactRole.BOM_POM && a.pomOnly()));

        // Step 2A: Stream M2 Layout ZIP
        ByteArrayOutputStream m2ZipOut = new ByteArrayOutputStream();
        zipStreamer.streamBundle(
                new BundleStreamRequest(resolved.artifacts(), BundleLayout.M2, true, true, true),
                m2ZipOut
        );
        Map<String, String> m2Entries = readZipEntries(m2ZipOut.toByteArray());
        assertTrue(m2Entries.containsKey("repository/com/acme/acme-web/1.0.0/acme-web-1.0.0.jar"));
        assertTrue(m2Entries.containsKey("repository/com/acme/acme-web/1.0.0/acme-web-1.0.0.pom"));
        assertTrue(m2Entries.containsKey("repository/com/acme/acme-core/1.0.0/acme-core-1.0.0-sources.jar"));
        assertTrue(m2Entries.containsKey("repository/com/acme/acme-parent/1.0.0/acme-parent-1.0.0.pom"));
        assertTrue(m2Entries.containsKey("repository/com/acme/acme-bom/1.0.0/acme-bom-1.0.0.pom"));
        assertTrue(m2Entries.containsKey("manifest.json"));
        assertTrue(m2Entries.containsKey("resolution-report.txt"));
        assertTrue(m2Entries.containsKey("upload.sh"));
        assertTrue(m2Entries.containsKey("upload.ps1"));

        // Step 2B: Stream FLAT Layout ZIP
        ByteArrayOutputStream flatZipOut = new ByteArrayOutputStream();
        zipStreamer.streamBundle(
                new BundleStreamRequest(resolved.artifacts(), BundleLayout.FLAT, true, true, true),
                flatZipOut
        );
        Map<String, String> flatEntries = readZipEntries(flatZipOut.toByteArray());
        assertTrue(flatEntries.containsKey("artifacts/com.acme__acme-web-1.0.0.jar"));
        assertTrue(flatEntries.containsKey("artifacts/com.acme__acme-core-1.0.0-sources.jar"));
        assertTrue(flatEntries.containsKey("artifacts/com.acme__acme-parent-1.0.0.pom"));
        assertTrue(flatEntries.containsKey("artifacts/com.acme__acme-bom-1.0.0.pom"));
        assertTrue(flatEntries.get("upload.sh").contains("com/acme/acme-parent/1.0.0/acme-parent-1.0.0.pom"));

        // Step 3: Verify Javalin HTTP server responds to GET / and GET /api/health
        var app = App.createApp(config).start(0);
        try {
            int port = app.port();
            var client = java.net.http.HttpClient.newHttpClient();
            var healthRes = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/health")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, healthRes.statusCode());
            assertTrue(healthRes.body().contains("\"status\":\"UP\""));

            var indexRes = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, indexRes.statusCode());
            assertTrue(indexRes.body().contains("Maven Air-Gap Dependency Bundler"));
        } finally {
            app.stop();
        }
    }

    private Map<String, String> readZipEntries(byte[] zipBytes) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
