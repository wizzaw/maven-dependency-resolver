package dev.airgap.bundler.model;

import java.util.List;

/**
 * Parsed representation of user input (GAV coordinates, XML <dependency> snippets, or full pom.xml).
 */
public record ParsedInput(
        List<CoordinateSpec> dependencies,
        List<CoordinateSpec> boms,
        List<CoordinateSpec> parentPoms,
        List<CoordinateSpec> plugins,
        List<String> warnings
) {
    public record CoordinateSpec(
            String groupId,
            String artifactId,
            String version,
            String classifier,
            String extension,
            String scope
    ) {
        public CoordinateSpec {
            classifier = (classifier == null) ? "" : classifier.trim();
            extension = (extension == null || extension.isBlank()) ? "jar" : extension.trim();
            scope = (scope == null || scope.isBlank()) ? "compile" : scope.trim();
        }

        public String toGavString() {
            if (classifier.isEmpty()) {
                return groupId + ":" + artifactId + ":" + version;
            }
            return groupId + ":" + artifactId + ":" + extension + ":" + classifier + ":" + version;
        }
    }
}
