package dev.airgap.bundler.parser;

import dev.airgap.bundler.model.ParsedInput;
import dev.airgap.bundler.model.ParsedInput.CoordinateSpec;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses mixed user inputs:
 * - Standard Maven GAV lines (groupId:artifactId:version, g:a:p:v, g:a:p:c:v)
 * - XML <dependency> / <dependencies> / <plugin> snippets
 * - Full pom.xml files (with <parent>, <properties>, <dependencyManagement>, <dependencies>, <plugins>)
 */
public class DependencyParser {

    private static final Pattern XML_BLOCK_PATTERN = Pattern.compile(
            "<(project|dependencies|dependencyManagement|dependency|plugins|plugin|parent|properties)\\b[^>]*>[\\s\\S]*?</\\1>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PROPERTY_PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    public ParsedInput parse(String rawInput, int maxDependencies) {
        if (rawInput == null || rawInput.isBlank()) {
            throw new IllegalArgumentException("Dependency input cannot be empty. Provide Maven coordinates (groupId:artifactId:version) or XML <dependency> blocks.");
        }

        Map<String, CoordinateSpec> dependencies = new LinkedHashMap<>();
        Map<String, CoordinateSpec> boms = new LinkedHashMap<>();
        Map<String, CoordinateSpec> parentPoms = new LinkedHashMap<>();
        Map<String, CoordinateSpec> plugins = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> properties = new HashMap<>();

        String trimmed = rawInput.trim();

        // Case 1: Full pom.xml (<project>...</project>)
        if (trimmed.contains("<project") && trimmed.contains("</project>")) {
            parseFullProjectXml(trimmed, dependencies, boms, parentPoms, plugins, properties, warnings);
        } else {
            // Case 2: Mixed XML blocks and/or coordinate lines
            StringBuilder remainingText = new StringBuilder(trimmed);

            // First extract any <properties> blocks so placeholders in <dependency> snippets work
            Matcher propMatcher = Pattern.compile("<properties\\b[^>]*>[\\s\\S]*?</properties>", Pattern.CASE_INSENSITIVE)
                    .matcher(remainingText.toString());
            while (propMatcher.find()) {
                parseXmlSnippet(propMatcher.group(), dependencies, boms, parentPoms, plugins, properties, warnings);
            }

            Matcher matcher = XML_BLOCK_PATTERN.matcher(remainingText.toString());
            StringBuffer afterXmlRemoved = new StringBuffer();
            while (matcher.find()) {
                String xmlChunk = matcher.group();
                parseXmlSnippet(xmlChunk, dependencies, boms, parentPoms, plugins, properties, warnings);
                matcher.appendReplacement(afterXmlRemoved, "\n");
            }
            matcher.appendTail(afterXmlRemoved);

            // Parse remaining non-XML lines as GAV coordinates
            parseCoordinateLines(afterXmlRemoved.toString(), dependencies, properties, warnings);
        }

        int totalRoots = dependencies.size() + boms.size() + parentPoms.size() + plugins.size();
        if (totalRoots == 0) {
            throw new IllegalArgumentException("No valid Maven coordinates found. Check your syntax (expected groupId:artifactId:version or <dependency> XML)."
                    + (warnings.isEmpty() ? "" : " Details: " + String.join("; ", warnings)));
        }
        if (totalRoots > maxDependencies) {
            throw new IllegalArgumentException("Input exceeds maximum allowed root dependencies (" + totalRoots + " > " + maxDependencies + ").");
        }

        return new ParsedInput(
                new ArrayList<>(dependencies.values()),
                new ArrayList<>(boms.values()),
                new ArrayList<>(parentPoms.values()),
                new ArrayList<>(plugins.values()),
                warnings
        );
    }

    private void parseFullProjectXml(
            String xml,
            Map<String, CoordinateSpec> dependencies,
            Map<String, CoordinateSpec> boms,
            Map<String, CoordinateSpec> parentPoms,
            Map<String, CoordinateSpec> plugins,
            Map<String, String> properties,
            List<String> warnings
    ) {
        try {
            Document doc = parseSecureXml("<root>" + stripXmlDeclaration(xml) + "</root>");
            NodeList projects = doc.getElementsByTagName("project");
            if (projects.getLength() == 0) {
                warnings.add("Could not locate <project> element in XML.");
                return;
            }
            Element project = (Element) projects.item(0);

            String projectGroupId = directChildText(project, "groupId");
            String projectArtifactId = directChildText(project, "artifactId");
            String projectVersion = directChildText(project, "version");

            // 1. Parent POM
            Element parentEl = directChildElement(project, "parent");
            if (parentEl != null) {
                String pGroup = directChildText(parentEl, "groupId");
                String pArtifact = directChildText(parentEl, "artifactId");
                String pVersion = directChildText(parentEl, "version");
                if (isNotBlank(pGroup) && isNotBlank(pArtifact) && isNotBlank(pVersion)) {
                    CoordinateSpec parentSpec = new CoordinateSpec(pGroup, pArtifact, pVersion, "", "pom", "compile");
                    parentPoms.put(parentSpec.toGavString(), parentSpec);
                    if (!isNotBlank(projectGroupId)) projectGroupId = pGroup;
                    if (!isNotBlank(projectVersion)) projectVersion = pVersion;
                }
            }

            if (isNotBlank(projectGroupId)) {
                properties.put("project.groupId", projectGroupId);
                properties.put("pom.groupId", projectGroupId);
            }
            if (isNotBlank(projectArtifactId)) {
                properties.put("project.artifactId", projectArtifactId);
            }
            if (isNotBlank(projectVersion)) {
                properties.put("project.version", projectVersion);
                properties.put("pom.version", projectVersion);
            }

            // 2. Properties
            Element propsEl = directChildElement(project, "properties");
            if (propsEl != null) {
                extractProperties(propsEl, properties);
            }

            // 3. dependencyManagement (extract BOMs and managed versions)
            Map<String, String> managedVersions = new HashMap<>();
            Element depMgmtEl = directChildElement(project, "dependencyManagement");
            if (depMgmtEl != null) {
                Element mgmtDepsEl = directChildElement(depMgmtEl, "dependencies");
                if (mgmtDepsEl != null) {
                    NodeList depNodes = mgmtDepsEl.getElementsByTagName("dependency");
                    for (int i = 0; i < depNodes.getLength(); i++) {
                        Element depEl = (Element) depNodes.item(i);
                        CoordinateSpec spec = extractDependencyElement(depEl, properties, Map.of(), warnings, true);
                        if (spec != null) {
                            if ("import".equalsIgnoreCase(spec.scope()) && "pom".equalsIgnoreCase(spec.extension())) {
                                boms.put(spec.toGavString(), spec);
                            } else if (isNotBlank(spec.version())) {
                                managedVersions.put(spec.groupId() + ":" + spec.artifactId(), spec.version());
                            }
                        }
                    }
                }
            }

            // 4. Direct <dependencies>
            Element depsEl = directChildElement(project, "dependencies");
            if (depsEl != null) {
                NodeList depNodes = depsEl.getChildNodes();
                for (int i = 0; i < depNodes.getLength(); i++) {
                    if (depNodes.item(i) instanceof Element depEl && "dependency".equals(depEl.getTagName())) {
                        CoordinateSpec spec = extractDependencyElement(depEl, properties, managedVersions, warnings, false);
                        if (spec != null) {
                            if ("import".equalsIgnoreCase(spec.scope()) && "pom".equalsIgnoreCase(spec.extension())) {
                                boms.put(spec.toGavString(), spec);
                            } else {
                                dependencies.put(spec.toGavString(), spec);
                            }
                        }
                    }
                }
            }

            // 5. Build Plugins
            Element buildEl = directChildElement(project, "build");
            if (buildEl != null) {
                Element pluginsEl = directChildElement(buildEl, "plugins");
                if (pluginsEl != null) {
                    extractPlugins(pluginsEl, properties, plugins, warnings);
                }
            }
        } catch (Exception e) {
            warnings.add("Failed to parse XML project: " + e.getMessage());
        }
    }

    private void parseXmlSnippet(
            String xmlSnippet,
            Map<String, CoordinateSpec> dependencies,
            Map<String, CoordinateSpec> boms,
            Map<String, CoordinateSpec> parentPoms,
            Map<String, CoordinateSpec> plugins,
            Map<String, String> properties,
            List<String> warnings
    ) {
        try {
            Document doc = parseSecureXml("<root>" + stripXmlDeclaration(xmlSnippet) + "</root>");

            // Properties
            NodeList propsNodes = doc.getElementsByTagName("properties");
            for (int i = 0; i < propsNodes.getLength(); i++) {
                extractProperties((Element) propsNodes.item(i), properties);
            }

            // Parent blocks
            NodeList parentNodes = doc.getElementsByTagName("parent");
            for (int i = 0; i < parentNodes.getLength(); i++) {
                Element parentEl = (Element) parentNodes.item(i);
                String g = interpolate(directChildText(parentEl, "groupId"), properties);
                String a = interpolate(directChildText(parentEl, "artifactId"), properties);
                String v = interpolate(directChildText(parentEl, "version"), properties);
                if (isNotBlank(g) && isNotBlank(a) && isNotBlank(v)) {
                    CoordinateSpec spec = new CoordinateSpec(g, a, v, "", "pom", "compile");
                    parentPoms.put(spec.toGavString(), spec);
                }
            }

            // Dependency blocks
            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element depEl = (Element) depNodes.item(i);
                // Skip <exclusion> children that might be confused if not careful (exclusions use <exclusion>)
                CoordinateSpec spec = extractDependencyElement(depEl, properties, Map.of(), warnings, false);
                if (spec != null) {
                    if ("import".equalsIgnoreCase(spec.scope()) && "pom".equalsIgnoreCase(spec.extension())) {
                        boms.put(spec.toGavString(), spec);
                    } else {
                        dependencies.put(spec.toGavString(), spec);
                    }
                }
            }

            // Plugin blocks
            NodeList pluginNodes = doc.getElementsByTagName("plugin");
            for (int i = 0; i < pluginNodes.getLength(); i++) {
                Element pluginEl = (Element) pluginNodes.item(i);
                extractSinglePlugin(pluginEl, properties, plugins, warnings);
            }
        } catch (Exception e) {
            warnings.add("Invalid XML block skipped: " + e.getMessage());
        }
    }

    private CoordinateSpec extractDependencyElement(
            Element depEl,
            Map<String, String> properties,
            Map<String, String> managedVersions,
            List<String> warnings,
            boolean inDepManagement
    ) {
        String groupId = interpolate(directChildText(depEl, "groupId"), properties);
        String artifactId = interpolate(directChildText(depEl, "artifactId"), properties);
        String version = interpolate(directChildText(depEl, "version"), properties);
        String classifier = interpolate(directChildText(depEl, "classifier"), properties);
        String type = interpolate(directChildText(depEl, "type"), properties);
        String scope = interpolate(directChildText(depEl, "scope"), properties);

        if (!isNotBlank(groupId) || !isNotBlank(artifactId)) {
            warnings.add("Skipped <dependency> missing groupId or artifactId.");
            return null;
        }

        if (!isNotBlank(version)) {
            version = managedVersions.get(groupId + ":" + artifactId);
        }

        if (!isNotBlank(version)) {
            if (!inDepManagement) {
                warnings.add("Skipped dependency '" + groupId + ":" + artifactId + "' because <version> is missing and could not be inferred.");
            }
            return null;
        }

        if (version.contains("${")) {
            warnings.add("Skipped dependency '" + groupId + ":" + artifactId + ":" + version + "' with unresolved property placeholder.");
            return null;
        }

        String extension = normalizeTypeToExtension(type);
        String normalizedScope = isNotBlank(scope) ? scope.toLowerCase() : "compile";
        return new CoordinateSpec(groupId, artifactId, version, classifier, extension, normalizedScope);
    }

    private void extractPlugins(
            Element pluginsEl,
            Map<String, String> properties,
            Map<String, CoordinateSpec> plugins,
            List<String> warnings
    ) {
        NodeList nodes = pluginsEl.getElementsByTagName("plugin");
        for (int i = 0; i < nodes.getLength(); i++) {
            extractSinglePlugin((Element) nodes.item(i), properties, plugins, warnings);
        }
    }

    private void extractSinglePlugin(
            Element pluginEl,
            Map<String, String> properties,
            Map<String, CoordinateSpec> plugins,
            List<String> warnings
    ) {
        String groupId = interpolate(directChildText(pluginEl, "groupId"), properties);
        if (!isNotBlank(groupId)) {
            groupId = "org.apache.maven.plugins";
        }
        String artifactId = interpolate(directChildText(pluginEl, "artifactId"), properties);
        String version = interpolate(directChildText(pluginEl, "version"), properties);

        if (isNotBlank(artifactId) && isNotBlank(version) && !version.contains("${")) {
            CoordinateSpec spec = new CoordinateSpec(groupId, artifactId, version, "", "jar", "compile");
            plugins.put(spec.toGavString(), spec);
        } else if (isNotBlank(artifactId)) {
            warnings.add("Skipped plugin '" + groupId + ":" + artifactId + "' without explicit <version>.");
        }
    }

    private void parseCoordinateLines(
            String text,
            Map<String, CoordinateSpec> dependencies,
            Map<String, String> properties,
            List<String> warnings
    ) {
        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            // Strip inline comments
            int hashIdx = line.indexOf('#');
            if (hashIdx > 0) {
                line = line.substring(0, hashIdx).trim();
            }
            // Strip Gradle-style DSL wrappers if pasted accidentally (e.g. implementation("g:a:v"))
            line = line.replaceAll("^(implementation|api|compileOnly|runtimeOnly|testImplementation)\\s*\\(?\\s*['\"]", "")
                    .replaceAll("['\"]\\s*\\)?[,;]?$", "")
                    .trim();

            if (line.isEmpty()) {
                continue;
            }

            // Support comma or whitespace separated coordinates on the same line
            String[] tokens = line.split("[\\s,;]+");
            for (String token : tokens) {
                if (token.isBlank()) continue;
                String interpolated = interpolate(token.trim(), properties);
                CoordinateSpec spec = parseSingleCoordinateToken(interpolated, warnings);
                if (spec != null) {
                    dependencies.put(spec.toGavString(), spec);
                }
            }
        }
    }

    private CoordinateSpec parseSingleCoordinateToken(String token, List<String> warnings) {
        String[] parts = token.split(":");
        if (parts.length < 3 || parts.length > 5) {
            warnings.add("Invalid coordinate format '" + token + "' (expected groupId:artifactId:version).");
            return null;
        }
        for (String part : parts) {
            if (part.isBlank()) {
                warnings.add("Invalid coordinate '" + token + "' with empty segment.");
                return null;
            }
        }

        String groupId = parts[0].trim();
        String artifactId = parts[1].trim();
        String extension = "jar";
        String classifier = "";
        String version;

        if (parts.length == 3) {
            // groupId:artifactId:version
            version = parts[2].trim();
        } else if (parts.length == 4) {
            // groupId:artifactId:packaging:version
            extension = normalizeTypeToExtension(parts[2].trim());
            version = parts[3].trim();
        } else {
            // groupId:artifactId:packaging:classifier:version
            extension = normalizeTypeToExtension(parts[2].trim());
            classifier = parts[3].trim();
            version = parts[4].trim();
        }

        return new CoordinateSpec(groupId, artifactId, version, classifier, extension, "compile");
    }

    public static String normalizeTypeToExtension(String type) {
        if (type == null || type.isBlank()) {
            return "jar";
        }
        String t = type.trim().toLowerCase();
        return switch (t) {
            case "bundle", "maven-plugin", "ejb", "ejb-client", "test-jar", "jar" -> "jar";
            case "pom" -> "pom";
            case "war" -> "war";
            case "ear" -> "ear";
            case "aar" -> "aar";
            default -> t;
        };
    }

    private static void extractProperties(Element propsEl, Map<String, String> properties) {
        NodeList children = propsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el) {
                properties.put(el.getTagName().trim(), el.getTextContent().trim());
            }
        }
    }

    public static String interpolate(String value, Map<String, String> properties) {
        if (value == null || value.isEmpty() || !value.contains("${")) {
            return value == null ? "" : value.trim();
        }
        String current = value.trim();
        for (int depth = 0; depth < 5; depth++) {
            Matcher m = PROPERTY_PLACEHOLDER.matcher(current);
            StringBuffer sb = new StringBuffer();
            boolean replaced = false;
            while (m.find()) {
                String key = m.group(1);
                String replacement = properties.get(key);
                if (replacement != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    replaced = true;
                }
            }
            m.appendTail(sb);
            current = sb.toString();
            if (!replaced || !current.contains("${")) {
                break;
            }
        }
        return current;
    }

    private static Document parseSecureXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new InputSource(new StringReader(xml)));
    }

    private static String stripXmlDeclaration(String xml) {
        return xml.replaceFirst("^\\s*<\\?xml[^>]*\\?>", "");
    }

    private static Element directChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el && tagName.equals(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    private static String directChildText(Element parent, String tagName) {
        Element child = directChildElement(parent, tagName);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
