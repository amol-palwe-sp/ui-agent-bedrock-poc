package com.sailpoint.poc.uiagent.ui;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point for {@code ./gradlew runUI}.
 *
 * <p>Starts a lightweight {@link HttpServer} on port 8080, registers all route handlers,
 * and shares mutable agent state across them via {@link ServerState}.
 */
public final class AgentUIServer {

    public static void main(String[] args) throws Exception {
        ServerState state = new ServerState();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        // Static assets
        server.createContext("/",            new StaticHandler("index.html", "text/html"));
        server.createContext("/style.css",   new StaticHandler("style.css",  "text/css"));
        server.createContext("/app.js",      new StaticHandler("app.js",     "application/javascript"));
        server.createContext("/sailpoint.png", new StaticHandler("sailpoint.png", "image/png"));
        server.createContext("/favicon.png", new StaticHandler("favicon.png", "image/png"));
        server.createContext("/favicon.ico", new StaticHandler("favicon.png", "image/png"));

        // API
        server.createContext("/api/stream",   new StreamHandler(state));
        server.createContext("/api/generate", new GenerateHandler(state));
        server.createContext("/api/run",      new RunHandler(state));
        server.createContext("/api/stop",     new RunHandler.StopHandler(state));
        server.createContext("/api/status",   new StatusHandler(state));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));

        server.start();
        System.out.println("UI available at http://localhost:8080");
        openBrowser("http://localhost:8080");
    }

    // ── Shared mutable state ──────────────────────────────────────────────────

    public static final class ServerState {
        public final BlockingQueue<String>    logQueue    = new LinkedBlockingQueue<>();
        public final AtomicReference<Thread>  agentThread = new AtomicReference<>();
        public final AtomicBoolean            agentRunning = new AtomicBoolean(false);
        public final AtomicReference<String>  lastGoalLine = new AtomicReference<>("");
    }

    // ── Static file handler ───────────────────────────────────────────────────

    private static final class StaticHandler implements HttpHandler {
        private final String resource;
        private final String contentType;

        StaticHandler(String resource, String contentType) {
            this.resource    = resource;
            this.contentType = contentType;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            try (InputStream in = AgentUIServer.class.getResourceAsStream("/ui/" + resource)) {
                if (in == null) {
                    byte[] body = ("Not found: /ui/" + resource).getBytes();
                    ex.sendResponseHeaders(404, body.length);
                    ex.getResponseBody().write(body);
                    return;
                }
                byte[] body = in.readAllBytes();
                ex.getResponseHeaders().set("Content-Type", contentType);
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }

    // ── GET /api/status ───────────────────────────────────────────────────────

    private static final class StatusHandler implements HttpHandler {
        private final ServerState state;

        StatusHandler(ServerState state) { this.state = state; }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String status = state.agentRunning.get() ? "running" : "ready";
            String json   = "{\"status\":\"" + status + "\"}";
            byte[] body   = json.getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    // ── Browser open helper ───────────────────────────────────────────────────

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {}

        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd;
        if (os.contains("mac")) {
            cmd = new String[]{"open", url};
        } else if (os.contains("nix") || os.contains("nux")) {
            cmd = new String[]{"xdg-open", url};
        } else {
            cmd = new String[]{"cmd", "/c", "start", url};
        }
        try {
            Runtime.getRuntime().exec(cmd);
        } catch (Exception ignored) {}
    }
}
