package dev.airgap.bundler.model;

import java.util.List;

/**
 * Request body for Step 1: POST /api/resolve
 */
public record ResolveRequest(
        String input,
        Boolean includeSources,
        Boolean includeJavadoc,
        Boolean includePlugins,
        List<String> scopes
) {
    public boolean effectiveIncludeSources() {
        return includeSources == null || includeSources;
    }

    public boolean effectiveIncludeJavadoc() {
        return includeJavadoc == null || includeJavadoc;
    }

    public boolean effectiveIncludePlugins() {
        return includePlugins == null || includePlugins;
    }

    public List<String> effectiveScopes() {
        if (scopes == null || scopes.isEmpty()) {
            return List.of("compile", "runtime");
        }
        return scopes;
    }
}
