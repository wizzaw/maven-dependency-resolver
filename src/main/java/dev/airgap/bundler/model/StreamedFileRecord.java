package dev.airgap.bundler.model;

/**
 * Metadata record for each file attempted/streamed into the ZIP bundle.
 */
public record StreamedFileRecord(
        String groupId,
        String artifactId,
        String version,
        String classifier,
        String fileType,
        String zipEntryPath,
        String m2RelativePath,
        String sha1,
        long sizeBytes,
        Status status,
        String note
) {
    public enum Status {
        INCLUDED,
        MISSING_OPTIONAL,
        FAILED
    }
}
