package dev.airgap.bundler.bundler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.airgap.bundler.model.ArtifactItem;
import dev.airgap.bundler.model.BundleLayout;
import dev.airgap.bundler.model.StreamedFileRecord;
import dev.airgap.bundler.model.StreamedFileRecord.Status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates manifest.json, resolution-report.txt, upload.sh, and upload.ps1
 * tailored to the exact artifacts streamed into the ZIP archive.
 */
public class ScriptGenerator {

    private final ObjectMapper mapper;

    public ScriptGenerator() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String generateManifestJson(
            BundleLayout layout,
            List<ArtifactItem> artifacts,
            List<StreamedFileRecord> fileRecords
    ) throws Exception {
        long includedCount = fileRecords.stream().filter(r -> r.status() == Status.INCLUDED).count();
        long missingOptCount = fileRecords.stream().filter(r -> r.status() == Status.MISSING_OPTIONAL).count();
        long failedCount = fileRecords.stream().filter(r -> r.status() == Status.FAILED).count();
        long totalBytes = fileRecords.stream().filter(r -> r.status() == Status.INCLUDED).mapToLong(StreamedFileRecord::sizeBytes).sum();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("layout", layout.name());
        root.put("totalArtifacts", artifacts.size());
        root.put("includedFilesCount", includedCount);
        root.put("missingOptionalFilesCount", missingOptCount);
        root.put("failedFilesCount", failedCount);
        root.put("totalSizeBytes", totalBytes);
        root.put("artifacts", artifacts);
        root.put("files", fileRecords);

        return mapper.writeValueAsString(root);
    }

    public String generateResolutionReport(
            BundleLayout layout,
            List<ArtifactItem> artifacts,
            List<StreamedFileRecord> fileRecords
    ) {
        StringBuilder sb = new StringBuilder();
        long included = fileRecords.stream().filter(r -> r.status() == Status.INCLUDED).count();
        long missingOptional = fileRecords.stream().filter(r -> r.status() == Status.MISSING_OPTIONAL).count();
        long failed = fileRecords.stream().filter(r -> r.status() == Status.FAILED).count();
        long totalBytes = fileRecords.stream().filter(r -> r.status() == Status.INCLUDED).mapToLong(StreamedFileRecord::sizeBytes).sum();

        sb.append("================================================================================\n");
        sb.append("                MAVEN AIR-GAP DEPENDENCY BUNDLE REPORT\n");
        sb.append("================================================================================\n");
        sb.append("Generated At          : ").append(Instant.now()).append("\n");
        sb.append("Archive Layout        : ").append(layout.name()).append("\n");
        sb.append("Total Resolved GAVs   : ").append(artifacts.size()).append("\n");
        sb.append("Included Files        : ").append(included).append("\n");
        sb.append("Missing Optional      : ").append(missingOptional).append(" (-sources.jar / -javadoc.jar not published)\n");
        sb.append("Failed Required Files : ").append(failed).append("\n");
        sb.append(String.format("Total Bundle Payload  : %.2f MB (%d bytes)%n", totalBytes / (1024.0 * 1024.0), totalBytes));
        sb.append("================================================================================\n\n");

        sb.append("1. RESOLVED ARTIFACTS (").append(artifacts.size()).append(")\n");
        sb.append("--------------------------------------------------------------------------------\n");
        for (ArtifactItem item : artifacts) {
            sb.append(String.format("  [%-10s] %s:%s:%s (packaging=%s%s)%n",
                    item.role().name(),
                    item.groupId(),
                    item.artifactId(),
                    item.version(),
                    item.packaging(),
                    item.classifier().isEmpty() ? "" : ", classifier=" + item.classifier()));
        }
        sb.append("\n");

        sb.append("2. INCLUDED FILES & SHA-1 CHECKSUMS (").append(included).append(")\n");
        sb.append("--------------------------------------------------------------------------------\n");
        for (StreamedFileRecord rec : fileRecords) {
            if (rec.status() == Status.INCLUDED) {
                sb.append(String.format("  %s  %s (%d bytes)%n",
                        rec.sha1(),
                        rec.zipEntryPath(),
                        rec.sizeBytes()));
            }
        }
        sb.append("\n");

        if (missingOptional > 0) {
            sb.append("3. MISSING OPTIONAL SOURCES / JAVADOCS (").append(missingOptional).append(")\n");
            sb.append("--------------------------------------------------------------------------------\n");
            for (StreamedFileRecord rec : fileRecords) {
                if (rec.status() == Status.MISSING_OPTIONAL) {
                    sb.append(String.format("  [SKIPPED] %s:%s:%s -> %s (%s)%n",
                            rec.groupId(), rec.artifactId(), rec.version(), rec.fileType(), rec.note()));
                }
            }
            sb.append("\n");
        }

        if (failed > 0) {
            sb.append("4. FAILED ARTIFACT DOWNLOADS (").append(failed).append(")\n");
            sb.append("--------------------------------------------------------------------------------\n");
            for (StreamedFileRecord rec : fileRecords) {
                if (rec.status() == Status.FAILED) {
                    sb.append(String.format("  [FAILED]  %s:%s:%s -> %s (%s)%n",
                            rec.groupId(), rec.artifactId(), rec.version(), rec.fileType(), rec.note()));
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    public String generateBashUploadScript(
            BundleLayout layout,
            List<StreamedFileRecord> fileRecords,
            boolean includeSha1
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("# ==============================================================================\n");
        sb.append("# Air-Gap Nexus Repository Upload Script (Layout: ").append(layout.name()).append(")\n");
        sb.append("# ==============================================================================\n");
        sb.append("# Usage:\n");
        sb.append("#   export NEXUS_URL=\"https://nexus.example.internal/repository/maven-releases\"\n");
        sb.append("#   export NEXUS_USER=\"admin\"\n");
        sb.append("#   export NEXUS_PASS=\"secret\"\n");
        sb.append("#   ./upload.sh\n");
        sb.append("#\n");
        sb.append("# Optional: Set DRY_RUN=true to preview uploads without executing HTTP requests.\n");
        sb.append("# ==============================================================================\n\n");
        sb.append("set -euo pipefail\n\n");
        sb.append("NEXUS_URL=\"${NEXUS_URL:-${1:-}}\"\n");
        sb.append("NEXUS_USER=\"${NEXUS_USER:-${2:-}}\"\n");
        sb.append("NEXUS_PASS=\"${NEXUS_PASS:-${3:-}}\"\n");
        sb.append("DRY_RUN=\"${DRY_RUN:-false}\"\n\n");
        sb.append("if [[ -z \"$NEXUS_URL\" ]]; then\n");
        sb.append("  echo \"ERROR: NEXUS_URL is required (e.g. https://nexus.internal/repository/maven-releases)\" >&2\n");
        sb.append("  echo \"Usage: NEXUS_URL=<url> NEXUS_USER=<user> NEXUS_PASS=<pass> ./upload.sh\" >&2\n");
        sb.append("  exit 1\n");
        sb.append("fi\n\n");
        sb.append("# Strip trailing slash from NEXUS_URL\n");
        sb.append("NEXUS_URL=\"${NEXUS_URL%/}\"\n\n");
        sb.append("SCRIPT_DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\n");
        sb.append("cd \"$SCRIPT_DIR\"\n\n");
        sb.append("UPLOADED=0\n");
        sb.append("FAILED=0\n\n");
        sb.append("upload_file() {\n");
        sb.append("  local local_file=\"$1\"\n");
        sb.append("  local target_path=\"$2\"\n");
        sb.append("  local full_url=\"${NEXUS_URL}/${target_path}\"\n\n");
        sb.append("  if [[ ! -f \"$local_file\" ]]; then\n");
        sb.append("    echo \"[WARN] Local file missing, skipping: $local_file\"\n");
        sb.append("    return 0\n");
        sb.append("  fi\n\n");
        sb.append("  if [[ \"$DRY_RUN\" == \"true\" ]]; then\n");
        sb.append("    echo \"[DRY-RUN] PUT $local_file -> $full_url\"\n");
        sb.append("    UPLOADED=$((UPLOADED + 1))\n");
        sb.append("    return 0\n");
        sb.append("  fi\n\n");
        sb.append("  echo \"[UPLOAD] $local_file -> $full_url\"\n");
        sb.append("  local auth_args=()\n");
        sb.append("  if [[ -n \"$NEXUS_USER\" ]]; then\n");
        sb.append("    auth_args=(-u \"${NEXUS_USER}:${NEXUS_PASS}\")\n");
        sb.append("  fi\n\n");
        sb.append("  if curl --fail -sS \"${auth_args[@]+\"${auth_args[@]}\"}\" --upload-file \"$local_file\" \"$full_url\"; then\n");
        sb.append("    UPLOADED=$((UPLOADED + 1))\n");
        sb.append("  else\n");
        sb.append("    echo \"[ERROR] Failed to upload $local_file to $full_url\" >&2\n");
        sb.append("    FAILED=$((FAILED + 1))\n");
        sb.append("  fi\n");
        sb.append("}\n\n");

        for (StreamedFileRecord rec : fileRecords) {
            if (rec.status() == Status.INCLUDED) {
                sb.append("upload_file ").append(shellQuote(rec.zipEntryPath()))
                        .append(" ").append(shellQuote(rec.m2RelativePath())).append("\n");
                if (includeSha1) {
                    sb.append("upload_file ").append(shellQuote(rec.zipEntryPath() + ".sha1"))
                            .append(" ").append(shellQuote(rec.m2RelativePath() + ".sha1")).append("\n");
                }
            }
        }

        sb.append("\necho \"==============================================================================\"\n");
        sb.append("echo \"Upload Complete: ${UPLOADED} succeeded, ${FAILED} failed.\"\n");
        sb.append("echo \"==============================================================================\"\n");
        sb.append("if [[ \"$FAILED\" -gt 0 ]]; then\n");
        sb.append("  exit 1\n");
        sb.append("fi\n");
        return sb.toString();
    }

    public String generatePowerShellUploadScript(
            BundleLayout layout,
            List<StreamedFileRecord> fileRecords,
            boolean includeSha1
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ==============================================================================\n");
        sb.append("# Air-Gap Nexus Repository Upload Script for PowerShell (Layout: ").append(layout.name()).append(")\n");
        sb.append("# ==============================================================================\n");
        sb.append("# Usage:\n");
        sb.append("#   .\\upload.ps1 -NexusUrl \"https://nexus.internal/repository/maven-releases\" -Username \"admin\" -Password \"secret\"\n");
        sb.append("# ==============================================================================\n\n");
        sb.append("param(\n");
        sb.append("    [Parameter(Mandatory=$true)][string]$NexusUrl,\n");
        sb.append("    [Parameter(Mandatory=$false)][string]$Username = $env:NEXUS_USER,\n");
        sb.append("    [Parameter(Mandatory=$false)][string]$Password = $env:NEXUS_PASS,\n");
        sb.append("    [switch]$DryRun\n");
        sb.append(")\n\n");
        sb.append("$ErrorActionPreference = \"Stop\"\n");
        sb.append("$NexusUrl = $NexusUrl.TrimEnd('/')\n");
        sb.append("Set-Location -Path $PSScriptRoot\n\n");
        sb.append("$Headers = @{}\n");
        sb.append("if ($Username) {\n");
        sb.append("    $Pair = \"${Username}:${Password}\"\n");
        sb.append("    $Encoded = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($Pair))\n");
        sb.append("    $Headers[\"Authorization\"] = \"Basic $Encoded\"\n");
        sb.append("}\n\n");
        sb.append("$Uploaded = 0\n");
        sb.append("$Failed = 0\n\n");
        sb.append("function Upload-Artifact {\n");
        sb.append("    param([string]$LocalFile, [string]$TargetPath)\n");
        sb.append("    $FullUrl = \"${NexusUrl}/${TargetPath}\"\n");
        sb.append("    if (-not (Test-Path -LiteralPath $LocalFile)) {\n");
        sb.append("        Write-Warning \"Local file missing, skipping: $LocalFile\"\n");
        sb.append("        return\n");
        sb.append("    }\n");
        sb.append("    if ($DryRun) {\n");
        sb.append("        Write-Host \"[DRY-RUN] PUT $LocalFile -> $FullUrl\"\n");
        sb.append("        $script:Uploaded++\n");
        sb.append("        return\n");
        sb.append("    }\n");
        sb.append("    try {\n");
        sb.append("        Write-Host \"[UPLOAD] $LocalFile -> $FullUrl\"\n");
        sb.append("        Invoke-RestMethod -Uri $FullUrl -Method Put -InFile $LocalFile -Headers $Headers -ContentType \"application/octet-stream\" | Out-Null\n");
        sb.append("        $script:Uploaded++\n");
        sb.append("    } catch {\n");
        sb.append("        Write-Error \"Failed to upload $LocalFile to $FullUrl : $_\"\n");
        sb.append("        $script:Failed++\n");
        sb.append("    }\n");
        sb.append("}\n\n");

        for (StreamedFileRecord rec : fileRecords) {
            if (rec.status() == Status.INCLUDED) {
                sb.append("Upload-Artifact -LocalFile ").append(psQuote(rec.zipEntryPath()))
                        .append(" -TargetPath ").append(psQuote(rec.m2RelativePath())).append("\n");
                if (includeSha1) {
                    sb.append("Upload-Artifact -LocalFile ").append(psQuote(rec.zipEntryPath() + ".sha1"))
                            .append(" -TargetPath ").append(psQuote(rec.m2RelativePath() + ".sha1")).append("\n");
                }
            }
        }

        sb.append("\nWrite-Host \"==============================================================================\"\n");
        sb.append("Write-Host \"Upload Complete: $Uploaded succeeded, $Failed failed.\"\n");
        sb.append("Write-Host \"==============================================================================\"\n");
        sb.append("if ($Failed -gt 0) { exit 1 }\n");
        return sb.toString();
    }

    private static String shellQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\"";
    }

    private static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
