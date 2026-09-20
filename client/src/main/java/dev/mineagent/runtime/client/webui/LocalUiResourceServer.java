package dev.mineagent.runtime.client.webui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Client-owned GET-only, immutable resource mounts. No filesystem or business RPC endpoint. */
public final class LocalUiResourceServer implements AutoCloseable {
    private record Mounted(Map<String, PackageUiResolver.Asset> assets, boolean trusted, long size) {}
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final Map<String, Mounted> mounts = new HashMap<>();
    private final int maxMounts;
    private final long maxBytes;
    private long retainedBytes;
    private boolean closed;

    public LocalUiResourceServer(int maxMounts, long maxBytes) throws IOException {
        if (maxMounts < 1 || maxBytes < 1) throw new IllegalArgumentException("UI_RESOURCE_BUDGET");
        this.maxMounts = maxMounts;
        this.maxBytes = maxBytes;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
            Thread thread = new Thread(r, "mineagent-ui-resources");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/", this::serve);
        server.start();
    }

    public synchronized Mount mount(Map<String, PackageUiResolver.Asset> assets, boolean trusted) {
        if (closed) throw new IllegalStateException("UI_RESOURCE_SERVER_CLOSED");
        if (mounts.size() >= maxMounts) throw new IllegalStateException("UI_WINDOW_BUDGET");
        if (assets.isEmpty()) throw new IllegalArgumentException("UI_RESOURCE_EMPTY");
        long size = 0;
        for (var entry : assets.entrySet()) {
            PackageUiResolver.requirePath(entry.getKey());
            size = Math.addExact(size, entry.getValue().size());
        }
        if (size > maxBytes - retainedBytes) throw new IllegalArgumentException("UI_RESOURCE_BUDGET");
        String id = UUID.randomUUID().toString();
        mounts.put(id, new Mounted(Map.copyOf(assets), trusted, size));
        retainedBytes += size;
        return new Mount(id, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + id + "/"));
    }

    public synchronized int mountCount() { return mounts.size(); }
    public synchronized long retainedBytes() { return retainedBytes; }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String expectedHost = "127.0.0.1:" + server.getAddress().getPort();
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                    || !expectedHost.equals(exchange.getRequestHeaders().getFirst("Host"))) {
                exchange.sendResponseHeaders(403, -1); return;
            }
            if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1); return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null || path.contains("%")) {
                exchange.sendResponseHeaders(404, -1); return;
            }
            String[] parts = path.split("/", 3);
            Mounted mounted;
            synchronized (this) { mounted = parts.length == 3 ? mounts.get(parts[1]) : null; }
            if (mounted == null) { exchange.sendResponseHeaders(404, -1); return; }
            try { PackageUiResolver.requirePath(parts[2]); }
            catch (IllegalArgumentException invalid) { exchange.sendResponseHeaders(404, -1); return; }
            var asset = mounted.assets().get(parts[2]);
            if (asset == null) { exchange.sendResponseHeaders(404, -1); return; }
            var headers = exchange.getResponseHeaders();
            headers.set("Content-Type", asset.mediaType() + (asset.mediaType().startsWith("text/") ? "; charset=utf-8" : ""));
            headers.set("Cache-Control", "no-store");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Content-Security-Policy", mounted.trusted()
                    ? "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-src 'self'; connect-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
                    : "sandbox allow-scripts allow-forms; default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
            // Opaque sandbox origins need CORS for browser JS modules. No cookies/credentials are accepted.
            if (!mounted.trusted()) headers.set("Access-Control-Allow-Origin", "null");
            if (exchange.getRequestMethod().equals("HEAD")) {
                headers.set("Content-Length", Integer.toString(asset.size()));
                exchange.sendResponseHeaders(200, -1);
            } else {
                byte[] bytes = asset.bytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }
    }

    public final class Mount implements AutoCloseable {
        private final String id;
        private final URI baseUri;
        private Mount(String id, URI baseUri) { this.id = id; this.baseUri = baseUri; }
        public URI baseUri() { return baseUri; }
        public URI entry(String path) { PackageUiResolver.requirePath(path); return baseUri.resolve(path); }
        @Override public void close() {
            synchronized (LocalUiResourceServer.this) {
                Mounted old = mounts.remove(id);
                if (old != null) retainedBytes -= old.size();
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        mounts.clear();
        retainedBytes = 0;
        server.stop(0);
        executor.shutdownNow();
    }
}
