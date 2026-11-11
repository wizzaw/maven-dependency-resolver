# Maven Air-Gap Dependency Bundler

A lightweight, zero-disk-streaming Java 21 web application that resolves Maven dependencies—including transitive dependencies, recursive `<parent>` POM hierarchies, imported `<dependencyManagement>` BOMs, `-sources.jar`, and `-javadoc.jar`—and streams a turnkey `.zip` archive ready to be pushed into an air-gapped **Sonatype Nexus Repository Manager**.

---

## Features

- **100% Native Maven Resolution:** Powered by the official **Apache Maven Resolver (Eclipse Aether)** and **Maven Model Builder** in-process.
- **Complete Ancestor & BOM Coverage:** Automatically walks every resolved artifact's `<parent>` POM hierarchy up to the root and inspects `<dependencyManagement>` for `import`-scoped BOM POMs so builds inside your air-gap never fail due to missing parent/BOM POMs.
- **2-Step Preview & Zero-Disk Streaming:**
  - **Step 1 (`POST /api/resolve`):** Resolves the dependency graph and previews every direct, transitive, parent POM, and BOM artifact in the UI.
  - **Step 2 (`POST /api/bundle/stream`):** Streams `.pom`, `.jar`, `-sources.jar` (best-effort), and `-javadoc.jar` (best-effort) directly from Maven Central into an HTTP `ZipOutputStream` with on-the-fly `.sha1` calculation and **0 bytes of temporary ZIP disk usage**.
- **Selectable ZIP Layout:**
  - **Standard `m2` Layout (`M2`):** `repository/group/id/artifact/version/artifact-version.jar`
  - **Flat Layout (`FLAT`):** `artifacts/group.id__artifact-version.jar`
- **Auto-Generated Air-Gap Upload Scripts:** Every ZIP bundle includes `upload.sh` (Bash + `curl`), `upload.ps1` (PowerShell), `manifest.json`, and `resolution-report.txt`.

---

## Quick Start

### Run with Maven & Java 21

```bash
mvn clean package
java -jar target/maven-airgap-bundler.jar
```

Open [http://localhost:8080](http://localhost:8080) in your browser.

### Run with Docker

```bash
docker build -t maven-airgap-bundler .
docker run --rm -p 8080:8080 maven-airgap-bundler
```

---

## Configuration (Environment Variables)

| Variable | Default | Description |
| :--- | :--- | :--- |
| `PORT` | `8080` | HTTP listen port |
| `MAVEN_CENTRAL_URL` | `https://repo.maven.apache.org/maven2` | Upstream Maven repository URL |
| `LOCAL_POM_CACHE_DIR` | `/tmp/maven-airgap-bundler-cache` | Local directory for caching resolved `.pom` metadata |
| `MAX_INPUT_DEPENDENCIES` | `50` | Maximum root coordinates / `<dependency>` items per request |
| `MAX_RESOLVED_ARTIFACTS` | `500` | Maximum total transitive GAVs per bundle |
| `MAX_CONCURRENT_STREAMS` | `5` | Maximum simultaneous ZIP download streams |
| `RATE_LIMIT_RESOLVE_PER_MIN` | `30` | Maximum requests per minute per client IP |
| `HTTP_CONNECT_TIMEOUT_SEC` | `10` | Connect timeout to Maven Central |
| `HTTP_READ_TIMEOUT_SEC` | `30` | Read timeout per artifact stream |

---

## Uploading the Bundle in an Air-Gapped Environment

1. Transfer the downloaded `maven-airgap-bundle-*.zip` into your air-gapped environment and extract it:
   ```bash
   unzip maven-airgap-bundle-*.zip -d maven-bundle
   cd maven-bundle
   ```
2. **Linux / macOS (`upload.sh`):**
   ```bash
   chmod +x upload.sh

   # Optional dry-run to preview all target Nexus URLs
   DRY_RUN=true NEXUS_URL="https://nexus.internal/repository/maven-releases" ./upload.sh

   # Upload all artifacts & checksums to Nexus
   NEXUS_URL="https://nexus.internal/repository/maven-releases" \
   NEXUS_USER="admin" \
   NEXUS_PASS="your-password" \
   ./upload.sh
   ```
3. **Windows PowerShell (`upload.ps1`):**
   ```powershell
   .\upload.ps1 `
     -NexusUrl "https://nexus.internal/repository/maven-releases" `
     -Username "admin" `
     -Password "your-password"
   ```
