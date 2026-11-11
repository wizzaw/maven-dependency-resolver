package dev.airgap.bundler.model;

/**
 * Output directory structure inside the generated ZIP archive.
 */
public enum BundleLayout {
    /**
     * Standard Maven 2 repository layout:
     * repository/group/id/artifactId/version/artifactId-version.jar
     */
    M2,

    /**
     * Flat layout:
     * artifacts/group.id__artifactId-version.jar
     */
    FLAT
}
