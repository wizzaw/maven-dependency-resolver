package dev.airgap.bundler.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Application configuration loaded from environment variables with safe, cost-efficient defaults.
 */
public record AppConfig(
        int port,
        String mavenCentralUrl,
        Path localCacheDir,
        int maxInputDependencies,
        int maxResolvedArtifacts,
        int maxConcurrentStreams,
        int rateLimitPerMinute,
        Duration httpConnectTimeout,
        Duration httpReadTimeout
) {
    public static AppConfig fromEnv() {
        int port = envInt("PORT", 8080);
        String centralUrl = envStr("MAVEN_CENTRAL_URL", "https://repo.maven.apache.org/maven2");
        if (centralUrl.endsWith("/")) {
            centralUrl = centralUrl.substring(0, centralUrl.length() - 1);
        }
        Path cacheDir = Paths.get(envStr("LOCAL_POM_CACHE_DIR",
                System.getProperty("java.io.tmpdir") + "/maven-airgap-bundler-cache"));
        int maxInputs = envInt("MAX_INPUT_DEPENDENCIES", 50);
        int maxResolved = envInt("MAX_RESOLVED_ARTIFACTS", 500);
        int maxStreams = envInt("MAX_CONCURRENT_STREAMS", 5);
        int rateLimit = envInt("RATE_LIMIT_RESOLVE_PER_MIN", 30);
        int connectTimeoutSec = envInt("HTTP_CONNECT_TIMEOUT_SEC", 10);
        int readTimeoutSec = envInt("HTTP_READ_TIMEOUT_SEC", 30);

        return new AppConfig(
                port,
                centralUrl,
                cacheDir,
                maxInputs,
                maxResolved,
                maxStreams,
                rateLimit,
                Duration.ofSeconds(connectTimeoutSec),
                Duration.ofSeconds(readTimeoutSec)
        );
    }

    private static String envStr(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val == null || val.isBlank()) ? defaultValue : val.trim();
    }

    private static int envInt(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
