package dev.airgap.bundler;

import dev.airgap.bundler.bundler.ScriptGenerator;
import dev.airgap.bundler.bundler.ZipStreamService;
import dev.airgap.bundler.config.AppConfig;
import dev.airgap.bundler.model.BundleStreamRequest;
import dev.airgap.bundler.model.ResolveRequest;
import dev.airgap.bundler.model.ResolveResponse;
import dev.airgap.bundler.parser.DependencyParser;
import dev.airgap.bundler.resolver.MavenResolverService;
import dev.airgap.bundler.security.RateLimiter;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Main entrypoint for the Air-Gap Maven Dependency Bundler web server.
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final DateTimeFormatter FILE_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();
        Javalin app = createApp(config);
        app.start(config.port());
        log.info("Maven Air-Gap Bundler listening on http://localhost:{}", config.port());
    }

    public static Javalin createApp(AppConfig config) {
        DependencyParser parser = new DependencyParser();
        MavenResolverService resolverService = new MavenResolverService(config, parser);
        ScriptGenerator scriptGenerator = new ScriptGenerator();
        ZipStreamService zipStreamService = new ZipStreamService(config, resolverService, scriptGenerator);
        RateLimiter rateLimiter = new RateLimiter(config.rateLimitPerMinute(), config.maxConcurrentStreams());

        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.staticFiles.add("/public", Location.CLASSPATH);
            javalinConfig.http.maxRequestSize = 2 * 1024 * 1024L; // 2 MB max payload
        });

        // Health & Configuration endpoint
        app.get("/api/health", ctx -> ctx.json(Map.of(
                "status", "UP",
                "mavenCentralUrl", config.mavenCentralUrl(),
                "limits", Map.of(
                        "maxInputDependencies", config.maxInputDependencies(),
                        "maxResolvedArtifacts", config.maxResolvedArtifacts(),
                        "maxConcurrentStreams", config.maxConcurrentStreams(),
                        "availableStreamSlots", rateLimiter.availableStreamSlots(),
                        "rateLimitPerMinute", config.rateLimitPerMinute()
                )
        )));

        // Step 1: Resolve Dependency Graph, Parent POMs, and BOMs
        app.post("/api/resolve", ctx -> {
            String clientIp = extractClientIp(ctx);
            if (!rateLimiter.tryAllowRequest(clientIp)) {
                ctx.status(HttpStatus.TOO_MANY_REQUESTS)
                        .json(Map.of("error", "Rate limit exceeded. Please wait a moment before resolving again."));
                return;
            }

            ResolveRequest req = ctx.bodyAsClass(ResolveRequest.class);
            ResolveResponse response = resolverService.resolve(req);
            ctx.json(response);
        });

        // Step 2: Stream ZIP Archive Directly to HTTP Response
        app.post("/api/bundle/stream", ctx -> {
            String clientIp = extractClientIp(ctx);
            if (!rateLimiter.tryAllowRequest(clientIp)) {
                ctx.status(HttpStatus.TOO_MANY_REQUESTS)
                        .json(Map.of("error", "Rate limit exceeded. Please wait before downloading another bundle."));
                return;
            }

            BundleStreamRequest req = ctx.bodyAsClass(BundleStreamRequest.class);
            if (req.artifacts() == null || req.artifacts().isEmpty()) {
                throw new IllegalArgumentException("Artifact list cannot be empty.");
            }

            if (!rateLimiter.tryAcquireStreamSlot(10)) {
                ctx.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .json(Map.of("error", "Server is currently at maximum concurrent download capacity. Please try again shortly."));
                return;
            }

            try {
                String layoutTag = req.effectiveLayout().name().toLowerCase();
                String fileName = "maven-airgap-bundle-" + layoutTag + "-" + LocalDateTime.now().format(FILE_TS_FORMAT) + ".zip";

                ctx.contentType("application/zip");
                ctx.header("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                ctx.header("Cache-Control", "no-store");
                ctx.header("X-Accel-Buffering", "no"); // Instructs Nginx/reverse proxies not to buffer the stream

                zipStreamService.streamBundle(req, ctx.res().getOutputStream());
            } finally {
                rateLimiter.releaseStreamSlot();
            }
        });

        // Global Error Handling
        app.exception(IllegalArgumentException.class, (e, ctx) -> {
            log.warn("Validation / resolution error: {}", e.getMessage());
            if (!ctx.res().isCommitted()) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", e.getMessage()));
            }
        });

        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unexpected error processing request", e);
            if (!ctx.res().isCommitted()) {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .json(Map.of("error", "Internal server error: " + e.getMessage()));
            }
        });

        return app;
    }

    private static String extractClientIp(Context ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return ctx.ip();
    }
}
