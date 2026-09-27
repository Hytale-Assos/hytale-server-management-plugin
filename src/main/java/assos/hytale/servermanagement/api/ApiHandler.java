package assos.hytale.servermanagement.api;

import assos.hytale.servermanagement.config.ManagementConfig;
import assos.hytale.servermanagement.whitelist.PlayerResolutionException;
import assos.hytale.servermanagement.whitelist.WhitelistClearResult;
import assos.hytale.servermanagement.whitelist.WhitelistEntry;
import assos.hytale.servermanagement.whitelist.WhitelistRemovalResult;
import assos.hytale.servermanagement.whitelist.WhitelistService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.hypixel.hytale.logger.HytaleLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Routes the management REST API.
 *
 * <pre>
 * GET    /api/v1/health
 * GET    /api/v1/status
 * POST   /api/v1/reload
 * GET    /api/v1/whitelist
 * GET    /api/v1/whitelist/status
 * POST   /api/v1/whitelist                 {"player":"NameOrUuid"}
 * DELETE /api/v1/whitelist/{identifier}
 * DELETE /api/v1/whitelist                 (clear all)
 * POST   /api/v1/whitelist/enable
 * POST   /api/v1/whitelist/disable
 * </pre>
 */
public class ApiHandler implements HttpHandler {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String BASE_PATH = "/api/v1";
    private static final long ACTION_TIMEOUT_SECONDS = 15L;
    private static final String RESPONSE_SENT_ATTRIBUTE = "assos.servermanagement.responseSent";

    private final ManagementConfig config;
    private final WhitelistService whitelist;
    private final ManagementFacade facade;

    public ApiHandler(@Nonnull ManagementConfig config, @Nonnull WhitelistService whitelist,
            @Nonnull ManagementFacade facade) {
        this.config = config;
        this.whitelist = whitelist;
        this.facade = facade;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            if (!isAuthorized(exchange)) {
                throw ApiException.unauthorized("Missing or invalid bearer token");
            }

            String[] segments = relativeSegments(path);
            route(exchange, method, segments);
        } catch (ApiException exception) {
            safeSendError(exchange, exception.getStatus(), exception.getCode(), exception.getMessage());
        } catch (PlayerResolutionException exception) {
            safeSendError(exchange, 404, "player_not_found", exception.getMessage());
        } catch (JsonSyntaxException exception) {
            safeSendError(exchange, 400, "invalid_json", "Request body is not valid JSON");
        } catch (IllegalArgumentException exception) {
            safeSendError(exchange, 400, "bad_request", exception.getMessage());
        } catch (Exception exception) {
            LOGGER.at(Level.WARNING).withCause(exception).log("Management API request failed: %s %s", method, path);
            safeSendError(exchange, 500, "internal_error", "Unexpected server error");
        } finally {
            exchange.close();
        }
    }

    /**
     * Sends an error response unless the connection was already terminated, for
     * example by a reload that restarted the HTTP server from this handler.
     */
    private void safeSendError(HttpExchange exchange, int status, String code, String message) {
        if (isResponseSent(exchange)) {
            return;
        }
        try {
            send(exchange, status, Json.error(code, message));
        } catch (IOException exception) {
            LOGGER.at(Level.FINE).withCause(exception).log("Failed to send error response for %s",
                    exchange.getRequestURI().getPath());
        }
    }

    private static boolean isResponseSent(HttpExchange exchange) {
        return Boolean.TRUE.equals(exchange.getAttribute(RESPONSE_SENT_ATTRIBUTE));
    }

    private void route(HttpExchange exchange, String method, String[] segments) throws Exception {
        if (segments.length == 0) {
            throw ApiException.notFound("Unknown endpoint");
        }

        if (segments.length == 1 && "health".equals(segments[0])) {
            requireMethod(method, "GET");
            handleHealth(exchange);
            return;
        }

        if (segments.length == 1 && "status".equals(segments[0])) {
            requireMethod(method, "GET");
            send(exchange, 200, facade.buildStatus());
            return;
        }

        if (segments.length == 1 && "reload".equals(segments[0])) {
            requireMethod(method, "POST");
            handleReload(exchange);
            return;
        }

        if (segments.length == 1 && "link".equals(segments[0])) {
            handleLink(exchange, method);
            return;
        }

        if (!"whitelist".equals(segments[0])) {
            throw ApiException.notFound("Unknown endpoint");
        }

        switch (segments.length) {
            case 1 -> handleWhitelistRoot(exchange, method);
            case 2 -> handleWhitelistAction(exchange, method, segments[1]);
            default -> throw ApiException.notFound("Unknown endpoint");
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        JsonObject root = Json.success();
        root.addProperty("status", "ok");
        root.addProperty("whitelistEnabled", whitelist.isEnabled());
        send(exchange, 200, root);
    }

    /**
     * Sends the acknowledgement first, then reloads on a separate thread. The
     * reload restarts the HTTP server, so it must not run while this exchange is
     * still being written.
     */
    private void handleReload(HttpExchange exchange) throws IOException {
        JsonObject root = Json.success();
        root.addProperty("message", "Configuration reloaded; the API is restarting");
        send(exchange, 200, root);

        Thread reloadThread = new Thread(() -> {
            try {
                Thread.sleep(200L);
                facade.reload();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                LOGGER.at(Level.WARNING).withCause(exception).log("Management API reload failed");
            }
        }, "HytaleServerManagement-Reload");
        reloadThread.setDaemon(true);
        reloadThread.start();
    }

    private void handleLink(HttpExchange exchange, String method) throws IOException {
        switch (method) {
            case "GET" -> send(exchange, 200, facade.buildLinkStatus());
            case "POST" -> send(exchange, 200, facade.linkApiCore());
            case "DELETE" -> send(exchange, 200, facade.unlinkApiCore());
            default -> throw ApiException.methodNotAllowed("Method " + method + " is not allowed here");
        }
    }

    private void handleWhitelistRoot(HttpExchange exchange, String method) throws Exception {
        switch (method) {
            case "GET" -> handleWhitelistList(exchange);
            case "POST" -> handleWhitelistAdd(exchange);
            case "DELETE" -> handleWhitelistClear(exchange);
            default -> throw ApiException.methodNotAllowed("Method " + method + " is not allowed here");
        }
    }

    private void handleWhitelistAction(HttpExchange exchange, String method, String action) throws Exception {
        switch (action) {
            case "status" -> {
                requireMethod(method, "GET");
                handleWhitelistStatus(exchange);
            }
            case "enable" -> {
                requireMethod(method, "POST");
                handleWhitelistToggle(exchange, true);
            }
            case "disable" -> {
                requireMethod(method, "POST");
                handleWhitelistToggle(exchange, false);
            }
            default -> {
                requireMethod(method, "DELETE");
                handleWhitelistRemove(exchange, action);
            }
        }
    }

    private void handleWhitelistList(HttpExchange exchange) throws IOException {
        List<WhitelistEntry> entries = whitelist.list();
        JsonArray array = Json.array();
        for (WhitelistEntry entry : entries) {
            array.add(toJson(entry));
        }
        JsonObject root = Json.success();
        root.addProperty("enabled", whitelist.isEnabled());
        root.addProperty("count", entries.size());
        root.add("entries", array);
        send(exchange, 200, root);
    }

    private void handleWhitelistStatus(HttpExchange exchange) throws IOException {
        List<WhitelistEntry> entries = whitelist.list();
        JsonObject root = Json.success();
        root.addProperty("enabled", whitelist.isEnabled());
        root.addProperty("count", entries.size());
        send(exchange, 200, root);
    }

    private void handleWhitelistAdd(HttpExchange exchange) throws Exception {
        JsonObject body = Json.parseObject(readBody(exchange));
        String player = Json.requireString(body, "player");
        WhitelistEntry entry = await(whitelist.add(player));
        JsonObject root = Json.success();
        root.addProperty("message", "Player added to the whitelist");
        root.add("entry", toJson(entry));
        send(exchange, 201, root);
    }

    private void handleWhitelistRemove(HttpExchange exchange, String identifier) throws Exception {
        WhitelistRemovalResult result = await(whitelist.remove(decode(identifier)));
        JsonObject root = Json.success();
        root.addProperty("message", "Player removed from the whitelist");
        root.addProperty("disconnected", result.disconnected());
        root.add("entry", toJson(result.entry()));
        send(exchange, 200, root);
    }

    private void handleWhitelistClear(HttpExchange exchange) throws Exception {
        WhitelistClearResult result = await(whitelist.clear());
        JsonObject root = Json.success();
        root.addProperty("message", "Whitelist cleared");
        root.addProperty("removed", result.removed());
        root.addProperty("disconnected", result.disconnected());
        send(exchange, 200, root);
    }

    private void handleWhitelistToggle(HttpExchange exchange, boolean enabled) throws Exception {
        boolean changed = await(whitelist.setEnabled(enabled));
        JsonObject root = Json.success();
        root.addProperty("enabled", enabled);
        root.addProperty("changed", changed);
        root.addProperty("message", enabled ? "Whitelist enabled" : "Whitelist disabled");
        send(exchange, 200, root);
    }

    private boolean isAuthorized(@Nonnull HttpExchange exchange) {
        if (!config.isApiRequireAuth()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String provided = header.substring("Bearer ".length()).trim();
        return constantTimeEquals(provided, config.getApiToken());
    }

    private static boolean constantTimeEquals(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    @Nonnull
    private static String[] relativeSegments(String path) {
        if (!path.startsWith(BASE_PATH)) {
            throw ApiException.notFound("Unknown endpoint");
        }
        String remainder = path.substring(BASE_PATH.length());
        if (remainder.startsWith("/")) {
            remainder = remainder.substring(1);
        }
        if (remainder.endsWith("/")) {
            remainder = remainder.substring(0, remainder.length() - 1);
        }
        if (remainder.isEmpty()) {
            return new String[0];
        }
        return remainder.split("/");
    }

    private static void requireMethod(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw ApiException.methodNotAllowed("Expected " + expected + " but received " + actual);
        }
    }

    @Nonnull
    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Nonnull
    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream stream = exchange.getRequestBody()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nonnull
    private static JsonObject toJson(WhitelistEntry entry) {
        JsonObject object = new JsonObject();
        object.addProperty("uuid", entry.uuidString());
        object.addProperty("username", entry.username());
        return object;
    }

    @Nonnull
    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        } catch (TimeoutException exception) {
            throw ApiException.internal("Operation timed out");
        }
    }

    private void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.setAttribute(RESPONSE_SENT_ATTRIBUTE, Boolean.TRUE);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (config.isLogRequests()) {
            LOGGER.at(Level.FINE).log("%s %s -> %d", exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), status);
        }
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(payload);
        }
    }
}
