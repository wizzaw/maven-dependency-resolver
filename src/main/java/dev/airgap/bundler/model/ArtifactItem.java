package dev.airgap.bundler.model;

import java.util.Objects;

/**
 * Represents a single resolved Maven artifact (dependency, parent POM, BOM, or plugin).
 */
public record ArtifactItem(
        String groupId,
        String artifactId,
        String version,
        String classifier,
        String extension,
        String packaging,
        String scope,
        ArtifactRole role,
        int depth,
        String m2DirectoryPath,
        boolean pomOnly
) {
    public ArtifactItem {
        Objects.requireNonNull(groupId, "groupId is required");
        Objects.requireNonNull(artifactId, "artifactId is required");
        Objects.requireNonNull(version, "version is required");
        classifier = (classifier == null) ? "" : classifier.trim();
        extension = (extension == null || extension.isBlank()) ? "jar" : extension.trim();
        packaging = (packaging == null || packaging.isBlank()) ? extension : packaging.trim();
        role = (role == null) ? ArtifactRole.TRANSITIVE : role;
        if (m2DirectoryPath == null || m2DirectoryPath.isBlank()) {
            m2DirectoryPath = groupId.replace('.', '/') + "/" + artifactId + "/" + version;
        }
    }

    /**
     * Canonical GAV(+classifier) key for deduplication.
     */
    public String coordinateKey() {
        if (classifier.isEmpty()) {
            return groupId + ":" + artifactId + ":" + extension + ":" + version;
        }
        return groupId + ":" + artifactId + ":" + extension + ":" + classifier + ":" + version;
    }

    /**
     * Base GAV key without classifier/extension (identifies the POM).
     */
    public String gavKey() {
        return groupId + ":" + artifactId + ":" + version;
    }

    /**
     * Base filename for the POM file (e.g., guava-33.3.1-jre.pom).
     */
    public String pomFileName() {
        return artifactId + "-" + version + ".pom";
    }

    /**
     * Base filename for the main binary artifact (e.g., guava-33.3.1-jre.jar).
     */
    public String mainFileName() {
        if (classifier.isEmpty()) {
            return artifactId + "-" + version + "." + extension;
        }
        return artifactId + "-" + version + "-" + classifier + "." + extension;
    }

    /**
     * Base filename for the sources jar (e.g., guava-33.3.1-jre-sources.jar).
     */
    public String sourcesFileName() {
        return artifactId + "-" + version + "-sources.jar";
    }

    /**
     * Base filename for the javadoc jar (e.g., guava-33.3.1-jre-javadoc.jar).
     */
    public String javadocFileName() {
        return artifactId + "-" + version + "-javadoc.jar";
    }
}
