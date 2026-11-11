package dev.airgap.bundler.model;

import java.util.List;

/**
 * Response body for Step 1: POST /api/resolve
 */
public record ResolveResponse(
        ResolutionSummary summary,
        List<ArtifactItem> artifacts,
        List<String> warnings
) {
    public record ResolutionSummary(
            int requestedRoots,
            int directArtifacts,
            int transitiveArtifacts,
            int parentPomArtifacts,
            int bomPomArtifacts,
            int pluginArtifacts,
            int totalJarArtifacts,
            int totalPomOnlyArtifacts,
            int totalItems,
            long resolutionTimeMs
    ) {
    }
}
