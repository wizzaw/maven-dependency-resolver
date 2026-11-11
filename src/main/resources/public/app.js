(() => {
    const PRESETS = {
        gav: [
            "# Standard Maven Coordinates (groupId:artifactId:version)",
            "org.apache.commons:commons-lang3:3.17.0",
            "com.google.guava:guava:33.3.1-jre",
            "org.slf4j:slf4j-simple:2.0.16"
        ].join("\n"),

        xml: [
            "<dependencies>",
            "  <dependency>",
            "    <groupId>com.fasterxml.jackson.core</groupId>",
            "    <artifactId>jackson-databind</artifactId>",
            "    <version>2.18.1</version>",
            "  </dependency>",
            "  <dependency>",
            "    <groupId>org.apache.commons</groupId>",
            "    <artifactId>commons-collections4</artifactId>",
            "    <version>4.4</version>",
            "  </dependency>",
            "</dependencies>"
        ].join("\n"),

        pom: [
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">",
            "  <modelVersion>4.0.0</modelVersion>",
            "  <groupId>com.example.airgap</groupId>",
            "  <artifactId>sample-service</artifactId>",
            "  <version>1.0.0</version>",
            "",
            "  <properties>",
            "    <slf4j.version>2.0.16</slf4j.version>",
            "    <junit.version>5.11.3</junit.version>",
            "  </properties>",
            "",
            "  <dependencyManagement>",
            "    <dependencies>",
            "      <dependency>",
            "        <groupId>org.junit</groupId>",
            "        <artifactId>junit-bom</artifactId>",
            "        <version>${junit.version}</version>",
            "        <type>pom</type>",
            "        <scope>import</scope>",
            "      </dependency>",
            "    </dependencies>",
            "  </dependencyManagement>",
            "",
            "  <dependencies>",
            "    <dependency>",
            "      <groupId>org.slf4j</groupId>",
            "      <artifactId>slf4j-api</artifactId>",
            "      <version>${slf4j.version}</version>",
            "    </dependency>",
            "  </dependencies>",
            "</project>"
        ].join("\n")
    };

    // State
    let resolvedArtifacts = [];
    let selectedKeys = new Set();
    let activeRoleFilter = "ALL";
    let searchQuery = "";

    // DOM Elements
    const serverStatusBadge = document.getElementById("serverStatusBadge");
    const quotaHint = document.getElementById("quotaHint");
    const resolveForm = document.getElementById("resolveForm");
    const dependencyInput = document.getElementById("dependencyInput");
    const pomFileInput = document.getElementById("pomFileInput");
    const clearBtn = document.getElementById("clearBtn");
    const resolveBtn = document.getElementById("resolveBtn");
    const resolveErrorBox = document.getElementById("resolveErrorBox");

    const emptyPreviewState = document.getElementById("emptyPreviewState");
    const resolvedContent = document.getElementById("resolvedContent");
    const resolutionTiming = document.getElementById("resolutionTiming");
    const warningsBox = document.getElementById("warningsBox");

    const metricTotal = document.getElementById("metricTotal");
    const metricJars = document.getElementById("metricJars");
    const metricPoms = document.getElementById("metricPoms");
    const metricSelected = document.getElementById("metricSelected");

    const artifactFilterInput = document.getElementById("artifactFilterInput");
    const selectAllCheckbox = document.getElementById("selectAllCheckbox");
    const artifactTableBody = document.getElementById("artifactTableBody");

    const downloadZipBtn = document.getElementById("downloadZipBtn");
    const streamStatusBox = document.getElementById("streamStatusBox");
    const streamStatusText = document.getElementById("streamStatusText");

    const nexusGuideDialog = document.getElementById("nexusGuideDialog");
    const openGuideBtn = document.getElementById("openGuideBtn");
    const closeGuideBtn = document.getElementById("closeGuideBtn");

    // Check health & limits on load
    async function checkHealth() {
        try {
            const res = await fetch("/api/health");
            if (!res.ok) throw new Error("Health check failed");
            const data = await res.json();
            serverStatusBadge.textContent = "Maven Central Ready";
            serverStatusBadge.classList.add("online");
            if (data.limits) {
                quotaHint.textContent = `Max ${data.limits.maxInputDependencies} root inputs / ${data.limits.maxResolvedArtifacts} resolved GAVs`;
            }
        } catch {
            serverStatusBadge.textContent = "Server Offline";
            serverStatusBadge.classList.remove("online");
        }
    }

    // Preset buttons
    document.querySelectorAll("[data-preset]").forEach(btn => {
        btn.addEventListener("click", () => {
            const key = btn.getAttribute("data-preset");
            if (PRESETS[key]) {
                dependencyInput.value = PRESETS[key];
                resolveErrorBox.hidden = true;
            }
        });
    });

    // File upload
    pomFileInput.addEventListener("change", async (e) => {
        const file = e.target.files?.[0];
        if (!file) return;
        const text = await file.text();
        dependencyInput.value = text;
        resolveErrorBox.hidden = true;
        pomFileInput.value = "";
    });

    clearBtn.addEventListener("click", () => {
        dependencyInput.value = "";
        resolveErrorBox.hidden = true;
        resolvedArtifacts = [];
        selectedKeys.clear();
        resolvedContent.hidden = true;
        resolutionTiming.hidden = true;
        emptyPreviewState.hidden = false;
    });

    // Modal Guide (<dialog>) with light-dismiss on backdrop click
    openGuideBtn.addEventListener("click", () => nexusGuideDialog.showModal());
    closeGuideBtn.addEventListener("click", () => nexusGuideDialog.close());
    nexusGuideDialog.addEventListener("click", (e) => {
        if (e.target === nexusGuideDialog) {
            nexusGuideDialog.close();
        }
    });

    // Step 1: Resolve Dependency Graph
    resolveForm.addEventListener("submit", async (e) => {
        e.preventDefault();
        resolveErrorBox.hidden = true;

        const input = dependencyInput.value.trim();
        if (!input) {
            showError("Please enter at least one Maven coordinate (groupId:artifactId:version) or XML <dependency> block.");
            return;
        }

        const scopes = Array.from(document.querySelectorAll('input[name="scope"]:checked')).map(cb => cb.value);
        const includeSources = document.getElementById("includeSources").checked;
        const includeJavadoc = document.getElementById("includeJavadoc").checked;
        const includePlugins = document.getElementById("includePlugins").checked;

        resolveBtn.disabled = true;
        resolveBtn.querySelector(".btn-text").textContent = "Resolving Graph & Parent POMs...";

        try {
            const response = await fetch("/api/resolve", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    input,
                    includeSources,
                    includeJavadoc,
                    includePlugins,
                    scopes
                })
            });

            const payload = await response.json();
            if (!response.ok) {
                throw new Error(payload.error || `Resolution failed (HTTP ${response.status})`);
            }

            renderResolutionResult(payload);
        } catch (err) {
            showError(err.message);
        } finally {
            resolveBtn.disabled = false;
            resolveBtn.querySelector(".btn-text").textContent = "Resolve Dependency Graph";
        }
    });

    function renderResolutionResult(payload) {
        resolvedArtifacts = payload.artifacts || [];
        selectedKeys = new Set(resolvedArtifacts.map(artifactKey));

        const summary = payload.summary || {};
        metricTotal.textContent = summary.totalItems ?? resolvedArtifacts.length;
        metricJars.textContent = summary.totalJarArtifacts ?? 0;
        metricPoms.textContent = (summary.parentPomArtifacts ?? 0) + (summary.bomPomArtifacts ?? 0);
        updateSelectedCount();

        if (summary.resolutionTimeMs !== undefined) {
            resolutionTiming.textContent = `Resolved in ${summary.resolutionTimeMs} ms`;
            resolutionTiming.hidden = false;
        }

        if (payload.warnings && payload.warnings.length > 0) {
            warningsBox.innerHTML = `<strong>Resolution Notes (${payload.warnings.length}):</strong><ul>` +
                payload.warnings.map(w => `<li>${escapeHtml(w)}</li>`).join("") + `</ul>`;
            warningsBox.hidden = false;
        } else {
            warningsBox.hidden = true;
        }

        emptyPreviewState.hidden = true;
        resolvedContent.hidden = false;
        renderArtifactTable();
    }

    function artifactKey(a) {
        return `${a.groupId}:${a.artifactId}:${a.extension}:${a.classifier || ""}:${a.version}`;
    }

    function getFilteredArtifacts() {
        return resolvedArtifacts.filter(item => {
            if (activeRoleFilter !== "ALL" && item.role !== activeRoleFilter) {
                return false;
            }
            if (searchQuery) {
                const hay = `${item.groupId}:${item.artifactId}:${item.version} ${item.role}`.toLowerCase();
                if (!hay.includes(searchQuery)) return false;
            }
            return true;
        });
    }

    function renderArtifactTable() {
        const filtered = getFilteredArtifacts();
        artifactTableBody.innerHTML = "";

        if (filtered.length === 0) {
            const tr = document.createElement("tr");
            tr.innerHTML = `<td colspan="5" style="text-align:center; padding: 1.5rem; color: var(--text-muted);">No matching artifacts for current filter.</td>`;
            artifactTableBody.appendChild(tr);
            selectAllCheckbox.checked = false;
            return;
        }

        let allFilteredSelected = true;

        for (const item of filtered) {
            const key = artifactKey(item);
            const isChecked = selectedKeys.has(key);
            if (!isChecked) allFilteredSelected = false;

            const tr = document.createElement("tr");
            const coordDisplay = `${item.groupId}:${item.artifactId}:${item.version}` +
                (item.classifier ? ` (${item.classifier})` : "");

            tr.innerHTML = `
                <td class="col-check">
                    <input type="checkbox" class="row-checkbox" data-key="${escapeHtml(key)}" ${isChecked ? "checked" : ""}>
                </td>
                <td><span class="role-tag role-${escapeHtml(item.role)}">${escapeHtml(item.role)}</span></td>
                <td class="coord-cell">${escapeHtml(coordDisplay)}</td>
                <td><code>${escapeHtml(item.packaging || item.extension)}</code></td>
                <td>${escapeHtml(item.scope || "—")}</td>
            `;
            artifactTableBody.appendChild(tr);
        }

        selectAllCheckbox.checked = allFilteredSelected;

        artifactTableBody.querySelectorAll(".row-checkbox").forEach(cb => {
            cb.addEventListener("change", () => {
                const k = cb.getAttribute("data-key");
                if (cb.checked) {
                    selectedKeys.add(k);
                } else {
                    selectedKeys.delete(k);
                }
                updateSelectedCount();
            });
        });
    }

    function updateSelectedCount() {
        metricSelected.textContent = selectedKeys.size;
        downloadZipBtn.disabled = selectedKeys.size === 0;
    }

    // Filter & Role Pill Listeners
    artifactFilterInput.addEventListener("input", (e) => {
        searchQuery = e.target.value.trim().toLowerCase();
        renderArtifactTable();
    });

    document.querySelectorAll(".filter-pill").forEach(pill => {
        pill.addEventListener("click", () => {
            document.querySelectorAll(".filter-pill").forEach(p => p.classList.remove("active"));
            pill.classList.add("active");
            activeRoleFilter = pill.getAttribute("data-role");
            renderArtifactTable();
        });
    });

    selectAllCheckbox.addEventListener("change", () => {
        const filtered = getFilteredArtifacts();
        for (const item of filtered) {
            const k = artifactKey(item);
            if (selectAllCheckbox.checked) {
                selectedKeys.add(k);
            } else {
                selectedKeys.delete(k);
            }
        }
        updateSelectedCount();
        renderArtifactTable();
    });

    // Step 2: Stream ZIP Bundle with Live Progress
    downloadZipBtn.addEventListener("click", async () => {
        const chosenArtifacts = resolvedArtifacts.filter(a => selectedKeys.has(artifactKey(a)));
        if (chosenArtifacts.length === 0) return;

        const layout = document.querySelector('input[name="bundleLayout"]:checked')?.value || "M2";
        const includeSources = document.getElementById("includeSources").checked;
        const includeJavadoc = document.getElementById("includeJavadoc").checked;
        const includeSha1 = document.getElementById("includeSha1").checked;

        downloadZipBtn.disabled = true;
        resolveBtn.disabled = true;
        streamStatusBox.hidden = false;
        streamStatusText.textContent = `Connecting & streaming ${chosenArtifacts.length} artifacts...`;

        try {
            const response = await fetch("/api/bundle/stream", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    artifacts: chosenArtifacts,
                    layout,
                    includeSources,
                    includeJavadoc,
                    includeSha1
                })
            });

            if (!response.ok) {
                let errMsg = `Bundle streaming failed (HTTP ${response.status})`;
                try {
                    const errJson = await response.json();
                    if (errJson.error) errMsg = errJson.error;
                } catch {}
                throw new Error(errMsg);
            }

            // Read stream chunks to display live byte progress to the user
            const reader = response.body.getReader();
            const chunks = [];
            let receivedBytes = 0;

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                chunks.push(value);
                receivedBytes += value.byteLength;
                const mb = (receivedBytes / (1024 * 1024)).toFixed(2);
                streamStatusText.textContent = `Streaming ZIP bundle... (${mb} MB received)`;
            }

            const blob = new Blob(chunks, { type: "application/zip" });
            const disposition = response.headers.get("Content-Disposition") || "";
            const match = disposition.match(/filename="([^"]+)"/);
            const fileName = match ? match[1] : `maven-airgap-bundle-${layout.toLowerCase()}.zip`;

            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            a.remove();
            URL.revokeObjectURL(url);

            streamStatusText.textContent = `Download complete (${(receivedBytes / (1024 * 1024)).toFixed(2)} MB)`;
        } catch (err) {
            showError(err.message);
            streamStatusBox.hidden = true;
        } finally {
            downloadZipBtn.disabled = selectedKeys.size === 0;
            resolveBtn.disabled = false;
        }
    });

    function showError(msg) {
        resolveErrorBox.textContent = msg;
        resolveErrorBox.hidden = false;
    }

    function escapeHtml(str) {
        return String(str ?? "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    checkHealth();
})();
