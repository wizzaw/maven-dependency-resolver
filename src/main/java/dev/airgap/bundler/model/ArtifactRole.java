package dev.airgap.bundler.model;

/**
 * Role of a resolved Maven artifact in the bundle.
 */
public enum ArtifactRole {
    DIRECT,
    TRANSITIVE,
    PARENT_POM,
    BOM_POM,
    PLUGIN
}
