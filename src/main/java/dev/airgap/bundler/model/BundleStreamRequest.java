package dev.airgap.bundler.model;

import java.util.List;

/**
 * Request body for Step 2: POST /api/bundle/stream
 */
public record BundleStreamRequest(
        List<ArtifactItem> artifacts,
        BundleLayout layout,
        Boolean includeSources,
        Boolean includeJavadoc,
        Boolean includeSha1
) {
    public BundleLayout effectiveLayout() {
        return layout == null ? BundleLayout.M2 : layout;
    }

    public boolean effectiveIncludeSources() {
        return includeSources == null || includeSources;
    }

    public boolean effectiveIncludeJavadoc() {
        return includeJavadoc == null || includeJavadoc;
    }

    public boolean effectiveIncludeSha1() {
        return includeSha1 == null || includeSha1;
    }
}
