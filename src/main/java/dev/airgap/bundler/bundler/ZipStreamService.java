package dev.airgap.bundler.bundler;

import dev.airgap.bundler.config.AppConfig;
import dev.airgap.bundler.model.ArtifactItem;
import dev.airgap.bundler.model.BundleLayout;
import dev.airgap.bundler.model.BundleStreamRequest;
import dev.airgap.bundler.model.StreamedFileRecord;
import dev.airgap.bundler.model.StreamedFileRecord.Status;
import dev.airgap.bundler.resolver.MavenResolverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams Maven artifacts (.pom, .jar, -sources.jar, -javadoc.jar) directly into an HTTP
 * ZipOutputStream with on-the-fly SHA-1 checksum calculation and zero temporary ZIP disk storage.
 */
public class ZipStreamService {

    private static final Logger log = LoggerFactory.getLogger(ZipStreamService.class);
    private static final int BUFFER_SIZE = 64 * 1024; // 64 KB streaming buffer

    private final AppConfig config;
    private final MavenResolverService resolverService;
    private final ScriptGenerator scriptGenerator;
    private final HttpClient httpClient;

    public ZipStreamService(
            AppConfig config,
            MavenResolverService resolverService,
            ScriptGenerator scriptGenerator
    ) {
        this.config = config;
        this.resolverService = resolverService;
        this.scriptGenerator = scriptGenerator;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.httpConnectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void streamBundle(BundleStreamRequest request, OutputStream outputStream) throws Exception {
        List<ArtifactItem> artifacts = request.artifacts();
        if (artifacts == null || artifacts.isEmpty()) {
            throw new IllegalArgumentException("No artifacts provided to bundle.");
        }
        if (artifacts.size() > config.maxResolvedArtifacts()) {
            throw new IllegalArgumentException(
                    "Artifact count exceeds maximum allowed (" + artifacts.size() + " > " + config.maxResolvedArtifacts() + ")."
            );
        }

        BundleLayout layout = request.effectiveLayout();
        boolean includeSources = request.effectiveIncludeSources();
        boolean includeJavadoc = request.effectiveIncludeJavadoc();
        boolean includeSha1 = request.effectiveIncludeSha1();

        List<StreamedFileRecord> fileRecords = new ArrayList<>();
        Set<String> writtenZipEntries = new HashSet<>();

        try (ZipOutputStream zos = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            // Since JARs are already compressed, BEST_SPEED avoids wasting CPU re-compressing Deflate streams
            zos.setLevel(Deflater.BEST_SPEED);

            for (ArtifactItem item : artifacts) {
                String dirPath = item.m2DirectoryPath();

                // 1. Always include the .pom file (deduplicated across classifiers of same GAV)
                String pomFileName = item.pomFileName();
                String pomM2Path = dirPath + "/" + pomFileName;
                String pomZipPath = buildZipEntryPath(layout, item.groupId(), pomM2Path, pomFileName);

                if (writtenZipEntries.add(pomZipPath)) {
                    File cachedPom = resolverService.getCachedPomFile(item.groupId(), item.artifactId(), item.version());
                    if (cachedPom != null && cachedPom.isFile()) {
                        streamLocalFileToZip(
                                zos, item, "pom", pomZipPath, pomM2Path, cachedPom, includeSha1, writtenZipEntries, fileRecords
                        );
                    } else {
                        streamRemoteFileToZip(
                                zos, item, "pom", pomZipPath, pomM2Path, true, includeSha1, writtenZipEntries, fileRecords
                        );
                    }
                }

                // 2. If not POM-only, stream the main binary artifact (.jar, .war, .aar, etc.)
                if (!item.pomOnly() && !"pom".equalsIgnoreCase(item.extension())) {
                    String mainFileName = item.mainFileName();
                    String mainM2Path = dirPath + "/" + mainFileName;
                    String mainZipPath = buildZipEntryPath(layout, item.groupId(), mainM2Path, mainFileName);

                    if (writtenZipEntries.add(mainZipPath)) {
                        streamRemoteFileToZip(
                                zos, item, item.extension(), mainZipPath, mainM2Path, true, includeSha1, writtenZipEntries, fileRecords
                        );
                    }

                    // 3. Best-effort -sources.jar
                    if (includeSources) {
                        String sourcesFileName = item.sourcesFileName();
                        String sourcesM2Path = dirPath + "/" + sourcesFileName;
                        String sourcesZipPath = buildZipEntryPath(layout, item.groupId(), sourcesM2Path, sourcesFileName);

                        if (writtenZipEntries.add(sourcesZipPath)) {
                            streamRemoteFileToZip(
                                    zos, item, "sources", sourcesZipPath, sourcesM2Path, false, includeSha1, writtenZipEntries, fileRecords
                            );
                        }
                    }

                    // 4. Best-effort -javadoc.jar
                    if (includeJavadoc) {
                        String javadocFileName = item.javadocFileName();
                        String javadocM2Path = dirPath + "/" + javadocFileName;
                        String javadocZipPath = buildZipEntryPath(layout, item.groupId(), javadocM2Path, javadocFileName);

                        if (writtenZipEntries.add(javadocZipPath)) {
                            streamRemoteFileToZip(
                                    zos, item, "javadoc", javadocZipPath, javadocM2Path, false, includeSha1, writtenZipEntries, fileRecords
                            );
                        }
                    }
                }
            }

            // 5. Write generated metadata & air-gap upload scripts at the root of the ZIP
            String manifestJson = scriptGenerator.generateManifestJson(layout, artifacts, fileRecords);
            writeTextEntry(zos, "manifest.json", manifestJson);

            String reportTxt = scriptGenerator.generateResolutionReport(layout, artifacts, fileRecords);
            writeTextEntry(zos, "resolution-report.txt", reportTxt);

            String bashScript = scriptGenerator.generateBashUploadScript(layout, fileRecords, includeSha1);
            writeTextEntry(zos, "upload.sh", bashScript);

            String psScript = scriptGenerator.generatePowerShellUploadScript(layout, fileRecords, includeSha1);
            writeTextEntry(zos, "upload.ps1", psScript);

            zos.finish();
        }
    }

    private void streamLocalFileToZip(
            ZipOutputStream zos,
            ArtifactItem item,
            String fileType,
            String zipEntryPath,
            String m2RelativePath,
            File localFile,
            boolean includeSha1,
            Set<String> writtenZipEntries,
            List<StreamedFileRecord> fileRecords
    ) {
        try (InputStream in = new FileInputStream(localFile)) {
            DigestAndSize result = writeStreamEntry(zos, zipEntryPath, in);
            if (includeSha1 && writtenZipEntries.add(zipEntryPath + ".sha1")) {
                writeTextEntry(zos, zipEntryPath + ".sha1", result.sha1());
            }
            fileRecords.add(new StreamedFileRecord(
                    item.groupId(),
                    item.artifactId(),
                    item.version(),
                    item.classifier(),
                    fileType,
                    zipEntryPath,
                    m2RelativePath,
                    result.sha1(),
                    result.sizeBytes(),
                    Status.INCLUDED,
                    "Cached local POM"
            ));
        } catch (Exception e) {
            log.warn("Failed to stream cached POM {}, falling back to remote: {}", localFile, e.getMessage());
            streamRemoteFileToZip(zos, item, fileType, zipEntryPath, m2RelativePath, true, includeSha1, writtenZipEntries, fileRecords);
        }
    }

    private void streamRemoteFileToZip(
            ZipOutputStream zos,
            ArtifactItem item,
            String fileType,
            String zipEntryPath,
            String m2RelativePath,
            boolean required,
            boolean includeSha1,
            Set<String> writtenZipEntries,
            List<StreamedFileRecord> fileRecords
    ) {
        String url = config.mavenCentralUrl() + "/" + m2RelativePath;
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(config.httpReadTimeout())
                    .header("User-Agent", "Maven-AirGap-Bundler/1.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();

            if (status == 200) {
                try (InputStream body = response.body()) {
                    DigestAndSize result = writeStreamEntry(zos, zipEntryPath, body);
                    if (includeSha1 && writtenZipEntries.add(zipEntryPath + ".sha1")) {
                        writeTextEntry(zos, zipEntryPath + ".sha1", result.sha1());
                    }
                    fileRecords.add(new StreamedFileRecord(
                            item.groupId(),
                            item.artifactId(),
                            item.version(),
                            item.classifier(),
                            fileType,
                            zipEntryPath,
                            m2RelativePath,
                            result.sha1(),
                            result.sizeBytes(),
                            Status.INCLUDED,
                            "HTTP 200"
                    ));
                }
            } else {
                try (InputStream body = response.body()) {
                    if (body != null) body.readAllBytes();
                }
                if (!required && status == 404) {
                    fileRecords.add(new StreamedFileRecord(
                            item.groupId(),
                            item.artifactId(),
                            item.version(),
                            item.classifier(),
                            fileType,
                            zipEntryPath,
                            m2RelativePath,
                            "",
                            0L,
                            Status.MISSING_OPTIONAL,
                            "Not published on Maven Central (HTTP 404)"
                    ));
                } else {
                    fileRecords.add(new StreamedFileRecord(
                            item.groupId(),
                            item.artifactId(),
                            item.version(),
                            item.classifier(),
                            fileType,
                            zipEntryPath,
                            m2RelativePath,
                            "",
                            0L,
                            Status.FAILED,
                            "HTTP " + status + " from " + url
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Error fetching {}: {}", url, e.getMessage());
            fileRecords.add(new StreamedFileRecord(
                    item.groupId(),
                    item.artifactId(),
                    item.version(),
                    item.classifier(),
                    fileType,
                    zipEntryPath,
                    m2RelativePath,
                    "",
                    0L,
                    required ? Status.FAILED : Status.MISSING_OPTIONAL,
                    "Error: " + e.getMessage()
            ));
        }
    }

    private record DigestAndSize(String sha1, long sizeBytes) {}

    private DigestAndSize writeStreamEntry(ZipOutputStream zos, String entryName, InputStream in) throws Exception {
        MessageDigest sha1Digest = MessageDigest.getInstance("SHA-1");
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);

        byte[] buffer = new byte[BUFFER_SIZE];
        long totalBytes = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            zos.write(buffer, 0, read);
            sha1Digest.update(buffer, 0, read);
            totalBytes += read;
        }
        zos.closeEntry();

        String sha1Hex = HexFormat.of().formatHex(sha1Digest.digest());
        return new DigestAndSize(sha1Hex, totalBytes);
    }

    private void writeTextEntry(ZipOutputStream zos, String entryName, String content) throws Exception {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zos.write(bytes);
        zos.closeEntry();
    }

    public static String buildZipEntryPath(BundleLayout layout, String groupId, String m2RelativePath, String fileName) {
        if (layout == BundleLayout.FLAT) {
            return "artifacts/" + groupId + "__" + fileName;
        }
        return "repository/" + m2RelativePath;
    }
}
