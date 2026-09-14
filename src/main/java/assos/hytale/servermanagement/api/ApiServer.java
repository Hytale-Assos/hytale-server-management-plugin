package assos.hytale.servermanagement.api;

import assos.hytale.servermanagement.config.ManagementConfig;
import assos.hytale.servermanagement.whitelist.WhitelistService;
import com.hypixel.hytale.logger.HytaleLogger;
import com.sun.net.httpserver.HttpServer;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Lightweight REST server built on the JDK's {@link HttpServer}. No third-party
 * HTTP dependency is bundled with the plugin.
 *
 * <p>By default the server binds to {@code 127.0.0.1} and requires a bearer
 * token. Binding to a non-loopback address is refused unless
 * {@code AllowRemoteManagement} is explicitly enabled.</p>
 */
public class ApiServer {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ManagementConfig config;
    private final WhitelistService whitelist;
    private final ManagementFacade facade;

    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(@Nonnull ManagementConfig config, @Nonnull WhitelistService whitelist,
            @Nonnull ManagementFacade facade) {
        this.config = config;
        this.whitelist = whitelist;
        this.facade = facade;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }

        String host = resolveHost();
        int port = config.getApiPort();
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("Invalid API port: " + port);
        }
        if (config.isApiRequireAuth() && (config.getApiToken() == null || config.getApiToken().isBlank())) {
            throw new IllegalStateException(
                    "API authentication is required but 'ApiToken' is empty; set a token in management_config.json");
        }

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "HytaleServerManagement-Api");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", new ApiHandler(config, whitelist, facade));
        server.start();
        LOGGER.at(Level.INFO).log("Management REST API listening on http://%s:%d", host, port);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Nonnull
    private String resolveHost() {
        String host = config.getApiHost();
        if (host == null || host.isBlank()) {
            return "127.0.0.1";
        }
        if (config.isAllowRemoteManagement()) {
            return host;
        }
        if (!isLoopback(host)) {
            LOGGER.at(Level.WARNING).log(
                    "Refusing to bind the management API to non-loopback host '%s'; falling back to 127.0.0.1. "
                            + "Set AllowRemoteManagement=true to override.",
                    host);
            return "127.0.0.1";
        }
        return host;
    }

    private static boolean isLoopback(@Nonnull String host) {
        return "127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }
}
