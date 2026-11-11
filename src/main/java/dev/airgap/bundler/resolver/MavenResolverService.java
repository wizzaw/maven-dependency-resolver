package dev.airgap.bundler.resolver;

import dev.airgap.bundler.config.AppConfig;
import dev.airgap.bundler.model.ArtifactItem;
import dev.airgap.bundler.model.ArtifactRole;
import dev.airgap.bundler.model.ParsedInput;
import dev.airgap.bundler.model.ParsedInput.CoordinateSpec;
import dev.airgap.bundler.model.ResolveRequest;
import dev.airgap.bundler.model.ResolveResponse;
import dev.airgap.bundler.model.ResolveResponse.ResolutionSummary;
import dev.airgap.bundler.parser.DependencyParser;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.graph.selector.ScopeDependencySelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;

/**
 * Resolves Maven dependency graphs, recursive Parent POM hierarchies, and imported BOM POMs
 * using the official Apache Maven Resolver (Eclipse Aether) + Maven Model Reader.
 */
public class MavenResolverService {

    private static final Logger log = LoggerFactory.getLogger(MavenResolverService.class);

    private final AppConfig config;
    private final DependencyParser parser;
    private final RepositorySystem repoSystem;
    private final List<RemoteRepository> remoteRepositories;

    public MavenResolverService(AppConfig config, DependencyParser parser) {
        this.config = config;
        this.parser = parser;
        this.repoSystem = new RepositorySystemSupplier().get();
        RemoteRepository central = new RemoteRepository.Builder(
                "central", "default", config.mavenCentralUrl()
        )
                .setSnapshotPolicy(new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_WARN))
                .setReleasePolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_WARN))
                .build();
        this.remoteRepositories = List.of(central);

        try {
            Files.createDirectories(config.localCacheDir());
        } catch (Exception e) {
            log.warn("Failed to create local POM cache directory {}: {}", config.localCacheDir(), e.getMessage());
        }
    }

    public ResolveResponse resolve(ResolveRequest request) {
        long startTime = System.currentTimeMillis();

        ParsedInput parsed = parser.parse(request.input(), config.maxInputDependencies());
        List<String> warnings = new ArrayList<>(parsed.warnings());

        Set<String> requestedScopes = new HashSet<>();
        for (String s : request.effectiveScopes()) {
            if (s != null && !s.isBlank()) {
                requestedScopes.add(s.trim().toLowerCase());
            }
        }
        if (requestedScopes.isEmpty()) {
            requestedScopes.add("compile");
            requestedScopes.add("runtime");
        }

        DefaultRepositorySystemSession session = newSession(requestedScopes);

        // LinkedHashMap keyed by coordinateKey (or gavKey for POM-only items)
        Map<String, ArtifactItem> resolvedItems = new LinkedHashMap<>();
        // Cache of parsed POM models keyed by GAV (groupId:artifactId:version)
        Map<String, Model> pomModelCache = new HashMap<>();

        // 1. Validate & pre-resolve root dependency POMs so typos are caught cleanly
        List<Dependency> validRootDeps = new ArrayList<>();
        for (CoordinateSpec spec : parsed.dependencies()) {
            File pomFile = resolvePomFile(session, spec.groupId(), spec.artifactId(), spec.version(), warnings);
            if (pomFile == null) {
                throw new IllegalArgumentException(
                        "Artifact not found on Maven Central: " + spec.toGavString()
                                + ". Verify the groupId, artifactId, and version."
                );
            }
            Model model = readPomModel(pomFile, spec.groupId() + ":" + spec.artifactId() + ":" + spec.version(), pomModelCache, warnings);
            String effectiveExt = spec.extension();
            if (model != null && "pom".equalsIgnoreCase(model.getPackaging())) {
                effectiveExt = "pom";
            }
            Artifact aetherArtifact = new DefaultArtifact(
                    spec.groupId(), spec.artifactId(), spec.classifier(), effectiveExt, spec.version()
            );
            validRootDeps.add(new Dependency(aetherArtifact, spec.scope()));
        }

        // 2. Prepare managed dependencies from any explicit BOMs
        List<Dependency> managedDeps = new ArrayList<>();
        for (CoordinateSpec bom : parsed.boms()) {
            File bomFile = resolvePomFile(session, bom.groupId(), bom.artifactId(), bom.version(), warnings);
            if (bomFile != null) {
                addPomOnlyArtifact(resolvedItems, bom.groupId(), bom.artifactId(), bom.version(), ArtifactRole.BOM_POM, 1);
                Artifact bomArtifact = new DefaultArtifact(bom.groupId(), bom.artifactId(), "", "pom", bom.version());
                try {
                    ArtifactDescriptorResult desc = repoSystem.readArtifactDescriptor(
                            session, new ArtifactDescriptorRequest(bomArtifact, remoteRepositories, null)
                    );
                    if (desc.getManagedDependencies() != null) {
                        managedDeps.addAll(desc.getManagedDependencies());
                    }
                } catch (Exception e) {
                    warnings.add("Could not read BOM descriptor for " + bom.toGavString() + ": " + e.getMessage());
                }
            } else {
                warnings.add("BOM POM not found on Maven Central: " + bom.toGavString());
            }
        }

        // 3. Collect transitive dependency graph for all validRootDeps
        if (!validRootDeps.isEmpty()) {
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setDependencies(validRootDeps);
            if (!managedDeps.isEmpty()) {
                collectRequest.setManagedDependencies(managedDeps);
            }
            collectRequest.setRepositories(remoteRepositories);

            try {
                CollectResult collectResult = repoSystem.collectDependencies(session, collectRequest);
                for (Exception ex : collectResult.getExceptions()) {
                    warnings.add("Dependency collection warning: " + ex.getMessage());
                }
                if (collectResult.getRoot() != null) {
                    traverseDependencyGraph(collectResult.getRoot(), resolvedItems, requestedScopes);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to resolve transitive dependency graph: " + e.getMessage(), e);
            }
        }

        // 4. Add explicit parent POMs from input (e.g. from pasted pom.xml)
        for (CoordinateSpec parentSpec : parsed.parentPoms()) {
            addPomOnlyArtifact(resolvedItems, parentSpec.groupId(), parentSpec.artifactId(), parentSpec.version(), ArtifactRole.PARENT_POM, 1);
        }

        // 5. Resolve requested Maven Plugins and their transitive runtime dependencies
        if (request.effectiveIncludePlugins() && !parsed.plugins().isEmpty()) {
            for (CoordinateSpec plugin : parsed.plugins()) {
                File pomFile = resolvePomFile(session, plugin.groupId(), plugin.artifactId(), plugin.version(), warnings);
                if (pomFile == null) {
                    warnings.add("Plugin not found on Maven Central: " + plugin.toGavString());
                    continue;
                }
                Artifact pluginArtifact = new DefaultArtifact(
                        plugin.groupId(), plugin.artifactId(), "", "jar", plugin.version()
                );
                ArtifactItem pluginItem = new ArtifactItem(
                        plugin.groupId(),
                        plugin.artifactId(),
                        plugin.version(),
                        "",
                        "jar",
                        "maven-plugin",
                        "runtime",
                        ArtifactRole.PLUGIN,
                        1,
                        null,
                        false
                );
                resolvedItems.putIfAbsent(pluginItem.coordinateKey(), pluginItem);

                // Collect plugin's required runtime dependencies
                try {
                    CollectRequest pluginCollect = new CollectRequest(new Dependency(pluginArtifact, "runtime"), remoteRepositories);
                    CollectResult pluginResult = repoSystem.collectDependencies(session, pluginCollect);
                    if (pluginResult.getRoot() != null) {
                        traverseDependencyGraph(pluginResult.getRoot(), resolvedItems, Set.of("compile", "runtime"));
                    }
                } catch (Exception e) {
                    warnings.add("Could not collect transitive dependencies for plugin " + plugin.toGavString() + ": " + e.getMessage());
                }
            }
        }

        // 6. Deep POM Inspection: Walk <parent> chains and <dependencyManagement> BOM imports for all artifacts
        walkAncestorsAndBoms(session, resolvedItems, pomModelCache, warnings);

        if (resolvedItems.size() > config.maxResolvedArtifacts()) {
            throw new IllegalArgumentException(
                    "Resolved dependency graph exceeds maximum allowed artifacts ("
                            + resolvedItems.size() + " > " + config.maxResolvedArtifacts() + ")."
            );
        }

        List<ArtifactItem> finalArtifacts = new ArrayList<>(resolvedItems.values());
        long elapsedMs = System.currentTimeMillis() - startTime;

        ResolutionSummary summary = buildSummary(
                parsed.dependencies().size() + parsed.boms().size() + parsed.parentPoms().size() + parsed.plugins().size(),
                finalArtifacts,
                elapsedMs
        );

        return new ResolveResponse(summary, finalArtifacts, warnings);
    }

    /**
     * Resolves a single .pom file into the local cache directory and returns the File handle if cached.
     */
    public File getCachedPomFile(String groupId, String artifactId, String version) {
        String relPath = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom";
        Path candidate = config.localCacheDir().resolve(relPath);
        if (Files.isRegularFile(candidate)) {
            return candidate.toFile();
        }
        return null;
    }

    private void traverseDependencyGraph(
            DependencyNode root,
            Map<String, ArtifactItem> resolvedItems,
            Set<String> requestedScopes
    ) {
        record NodeWithDepth(DependencyNode node, int depth) {}
        Queue<NodeWithDepth> queue = new ArrayDeque<>();
        for (DependencyNode child : root.getChildren()) {
            queue.add(new NodeWithDepth(child, 1));
        }

        Set<String> visitedNodes = new HashSet<>();

        while (!queue.isEmpty()) {
            NodeWithDepth current = queue.poll();
            DependencyNode node = current.node();
            int depth = current.depth();

            Dependency dep = node.getDependency();
            if (dep != null && dep.getArtifact() != null) {
                Artifact a = dep.getArtifact();
                String scope = (dep.getScope() == null || dep.getScope().isBlank()) ? "compile" : dep.getScope().toLowerCase();

                if (depth == 1 || requestedScopes.contains(scope)) {
                    String ext = DependencyParser.normalizeTypeToExtension(a.getExtension());
                    boolean pomOnly = "pom".equalsIgnoreCase(ext);
                    ArtifactRole role = (depth == 1) ? ArtifactRole.DIRECT : ArtifactRole.TRANSITIVE;

                    ArtifactItem item = new ArtifactItem(
                            a.getGroupId(),
                            a.getArtifactId(),
                            a.getBaseVersion(),
                            a.getClassifier(),
                            ext,
                            ext,
                            scope,
                            role,
                            depth,
                            null,
                            pomOnly
                    );
                    resolvedItems.putIfAbsent(item.coordinateKey(), item);
                }

                String nodeKey = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getExtension() + ":" + a.getClassifier() + ":" + a.getBaseVersion();
                if (!visitedNodes.add(nodeKey)) {
                    continue;
                }
            }

            if (resolvedItems.size() >= config.maxResolvedArtifacts()) {
                break;
            }

            for (DependencyNode child : node.getChildren()) {
                queue.add(new NodeWithDepth(child, depth + 1));
            }
        }
    }

    private void walkAncestorsAndBoms(
            DefaultRepositorySystemSession session,
            Map<String, ArtifactItem> resolvedItems,
            Map<String, Model> pomModelCache,
            List<String> warnings
    ) {
        record GavDepth(String groupId, String artifactId, String version, int depth) {
            String gav() {
                return groupId + ":" + artifactId + ":" + version;
            }
        }

        Queue<GavDepth> pomQueue = new ArrayDeque<>();
        Set<String> inspectedPoms = new HashSet<>();

        // Seed queue with all currently known artifacts
        for (ArtifactItem item : new ArrayList<>(resolvedItems.values())) {
            pomQueue.add(new GavDepth(item.groupId(), item.artifactId(), item.version(), item.depth()));
        }

        while (!pomQueue.isEmpty() && resolvedItems.size() < config.maxResolvedArtifacts()) {
            GavDepth current = pomQueue.poll();
            if (!inspectedPoms.add(current.gav())) {
                continue;
            }

            File pomFile = resolvePomFile(session, current.groupId(), current.artifactId(), current.version(), warnings);
            if (pomFile == null) {
                continue;
            }

            Model model = readPomModel(pomFile, current.gav(), pomModelCache, warnings);
            if (model == null) {
                continue;
            }

            // If this artifact was recorded as a .jar dependency, check if its POM actually declares <packaging>pom</packaging>
            if ("pom".equalsIgnoreCase(model.getPackaging())) {
                String jarKey = current.groupId() + ":" + current.artifactId() + ":jar:" + current.version();
                ArtifactItem existing = resolvedItems.get(jarKey);
                if (existing != null && !existing.pomOnly()) {
                    resolvedItems.remove(jarKey);
                    ArtifactItem updated = new ArtifactItem(
                            existing.groupId(),
                            existing.artifactId(),
                            existing.version(),
                            existing.classifier(),
                            "pom",
                            "pom",
                            existing.scope(),
                            existing.role(),
                            existing.depth(),
                            existing.m2DirectoryPath(),
                            true
                    );
                    resolvedItems.put(updated.coordinateKey(), updated);
                }
            }

            // Collect effective properties up the parent chain for property interpolation
            Map<String, String> effectiveProps = collectEffectiveProperties(session, model, current.groupId(), current.version(), pomModelCache, warnings);

            // 1. Check <parent> POM
            Parent parent = model.getParent();
            if (parent != null && isNotBlank(parent.getGroupId()) && isNotBlank(parent.getArtifactId()) && isNotBlank(parent.getVersion())) {
                String pGroup = DependencyParser.interpolate(parent.getGroupId().trim(), effectiveProps);
                String pArtifact = DependencyParser.interpolate(parent.getArtifactId().trim(), effectiveProps);
                String pVersion = DependencyParser.interpolate(parent.getVersion().trim(), effectiveProps);

                if (!pVersion.contains("${")) {
                    addPomOnlyArtifact(resolvedItems, pGroup, pArtifact, pVersion, ArtifactRole.PARENT_POM, current.depth() + 1);
                    String parentGav = pGroup + ":" + pArtifact + ":" + pVersion;
                    if (!inspectedPoms.contains(parentGav)) {
                        pomQueue.add(new GavDepth(pGroup, pArtifact, pVersion, current.depth() + 1));
                    }
                }
            }

            // 2. Check <dependencyManagement> for imported BOMs (scope=import, type=pom)
            DependencyManagement depMgmt = model.getDependencyManagement();
            if (depMgmt != null && depMgmt.getDependencies() != null) {
                for (org.apache.maven.model.Dependency dep : depMgmt.getDependencies()) {
                    String scope = DependencyParser.interpolate(dep.getScope(), effectiveProps);
                    String type = DependencyParser.interpolate(dep.getType(), effectiveProps);
                    if ("import".equalsIgnoreCase(scope) && "pom".equalsIgnoreCase(type)) {
                        String bGroup = DependencyParser.interpolate(dep.getGroupId(), effectiveProps);
                        String bArtifact = DependencyParser.interpolate(dep.getArtifactId(), effectiveProps);
                        String bVersion = DependencyParser.interpolate(dep.getVersion(), effectiveProps);

                        if (isNotBlank(bGroup) && isNotBlank(bArtifact) && isNotBlank(bVersion) && !bVersion.contains("${")) {
                            addPomOnlyArtifact(resolvedItems, bGroup, bArtifact, bVersion, ArtifactRole.BOM_POM, current.depth() + 1);
                            String bomGav = bGroup + ":" + bArtifact + ":" + bVersion;
                            if (!inspectedPoms.contains(bomGav)) {
                                pomQueue.add(new GavDepth(bGroup, bArtifact, bVersion, current.depth() + 1));
                            }
                        }
                    }
                }
            }
        }
    }

    private Map<String, String> collectEffectiveProperties(
            DefaultRepositorySystemSession session,
            Model startModel,
            String fallbackGroupId,
            String fallbackVersion,
            Map<String, Model> pomModelCache,
            List<String> warnings
    ) {
        List<Model> hierarchy = new ArrayList<>();
        Model curr = startModel;
        Set<String> visited = new HashSet<>();

        while (curr != null && hierarchy.size() < 15) {
            hierarchy.add(curr);
            Parent p = curr.getParent();
            if (p == null || !isNotBlank(p.getGroupId()) || !isNotBlank(p.getArtifactId()) || !isNotBlank(p.getVersion())) {
                break;
            }
            String pGav = p.getGroupId().trim() + ":" + p.getArtifactId().trim() + ":" + p.getVersion().trim();
            if (!visited.add(pGav)) {
                break;
            }
            Model parentModel = pomModelCache.get(pGav);
            if (parentModel == null) {
                File pFile = resolvePomFile(session, p.getGroupId().trim(), p.getArtifactId().trim(), p.getVersion().trim(), warnings);
                if (pFile != null) {
                    parentModel = readPomModel(pFile, pGav, pomModelCache, warnings);
                }
            }
            curr = parentModel;
        }

        // Apply properties from root ancestor down to child so child overrides parent
        Map<String, String> props = new HashMap<>();
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Model m = hierarchy.get(i);
            Properties modelProps = m.getProperties();
            if (modelProps != null) {
                for (String name : modelProps.stringPropertyNames()) {
                    props.put(name, modelProps.getProperty(name));
                }
            }
        }

        String effectiveGroup = isNotBlank(startModel.getGroupId()) ? startModel.getGroupId() : fallbackGroupId;
        String effectiveVersion = isNotBlank(startModel.getVersion()) ? startModel.getVersion() : fallbackVersion;

        props.put("project.groupId", effectiveGroup);
        props.put("pom.groupId", effectiveGroup);
        props.put("project.artifactId", startModel.getArtifactId());
        props.put("project.version", effectiveVersion);
        props.put("pom.version", effectiveVersion);

        if (startModel.getParent() != null && isNotBlank(startModel.getParent().getVersion())) {
            props.put("project.parent.version", startModel.getParent().getVersion());
            props.put("parent.version", startModel.getParent().getVersion());
        }

        return props;
    }

    private void addPomOnlyArtifact(
            Map<String, ArtifactItem> resolvedItems,
            String groupId,
            String artifactId,
            String version,
            ArtifactRole role,
            int depth
    ) {
        // Check if this GAV is already included as a JAR or POM artifact
        String jarKey = groupId + ":" + artifactId + ":jar:" + version;
        String pomKey = groupId + ":" + artifactId + ":pom:" + version;
        if (resolvedItems.containsKey(jarKey) || resolvedItems.containsKey(pomKey)) {
            return;
        }
        ArtifactItem pomItem = new ArtifactItem(
                groupId,
                artifactId,
                version,
                "",
                "pom",
                "pom",
                null,
                role,
                depth,
                null,
                true
        );
        resolvedItems.put(pomKey, pomItem);
    }

    private File resolvePomFile(
            DefaultRepositorySystemSession session,
            String groupId,
            String artifactId,
            String version,
            List<String> warnings
    ) {
        File cached = getCachedPomFile(groupId, artifactId, version);
        if (cached != null) {
            return cached;
        }
        try {
            Artifact pomArtifact = new DefaultArtifact(groupId, artifactId, "", "pom", version);
            ArtifactRequest req = new ArtifactRequest(pomArtifact, remoteRepositories, null);
            ArtifactResult res = repoSystem.resolveArtifact(session, req);
            return res.getArtifact() != null ? res.getArtifact().getFile() : null;
        } catch (Exception e) {
            warnings.add("Could not resolve POM for " + groupId + ":" + artifactId + ":" + version + " (" + e.getMessage() + ")");
            return null;
        }
    }

    private Model readPomModel(
            File pomFile,
            String gavKey,
            Map<String, Model> pomModelCache,
            List<String> warnings
    ) {
        Model cached = pomModelCache.get(gavKey);
        if (cached != null) {
            return cached;
        }
        try (FileReader reader = new FileReader(pomFile, StandardCharsets.UTF_8)) {
            MavenXpp3Reader xpp3 = new MavenXpp3Reader();
            Model model = xpp3.read(reader);
            pomModelCache.put(gavKey, model);
            return model;
        } catch (Exception e) {
            warnings.add("Failed to parse POM XML for " + gavKey + ": " + e.getMessage());
            return null;
        }
    }

    private DefaultRepositorySystemSession newSession(Set<String> requestedScopes) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(config.localCacheDir().toFile());
        session.setLocalRepositoryManager(repoSystem.newLocalRepositoryManager(session, localRepo));
        session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_WARN);
        session.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_NEVER);

        // Determine excluded scopes based on user selection
        List<String> allScopes = List.of("compile", "runtime", "provided", "test", "system");
        Collection<String> excludedScopes = new ArrayList<>();
        for (String s : allScopes) {
            if (!requestedScopes.contains(s)) {
                excludedScopes.add(s);
            }
        }

        session.setDependencySelector(new AndDependencySelector(
                new ScopeDependencySelector(null, excludedScopes),
                new OptionalDependencySelector(),
                new ExclusionDependencySelector()
        ));

        return session;
    }

    private static ResolutionSummary buildSummary(int requestedRoots, List<ArtifactItem> items, long elapsedMs) {
        int direct = 0;
        int transitive = 0;
        int parentPoms = 0;
        int bomPoms = 0;
        int plugins = 0;
        int totalJars = 0;
        int totalPomOnly = 0;

        for (ArtifactItem item : items) {
            switch (item.role()) {
                case DIRECT -> direct++;
                case TRANSITIVE -> transitive++;
                case PARENT_POM -> parentPoms++;
                case BOM_POM -> bomPoms++;
                case PLUGIN -> plugins++;
            }
            if (item.pomOnly()) {
                totalPomOnly++;
            } else {
                totalJars++;
            }
        }

        return new ResolutionSummary(
                requestedRoots,
                direct,
                transitive,
                parentPoms,
                bomPoms,
                plugins,
                totalJars,
                totalPomOnly,
                items.size(),
                elapsedMs
        );
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
