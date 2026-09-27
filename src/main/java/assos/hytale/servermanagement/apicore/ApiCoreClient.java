package assos.hytale.servermanagement.apicore;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Internal client pushing player session events to the central {@code api-core}.
 *
 * <p>It authenticates as a module: the API key travels in {@code X-Api-Key} and
 * write requests are signed with HMAC-SHA256 over {@code "{timestamp}.{body}"}.
 * No outbound HTTP dependency is added: this uses the JDK's
 * {@link java.net.http.HttpClient}.</p>
 *
 * <p>Delivery is fire-and-forget on a dedicated daemon executor so the game
 * thread is never blocked by the network.</p>
 */
public class ApiCoreClient {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson GSON = new Gson();
    private static final String SESSION_STARTED = "session_started";
    private static final String SESSION_ENDED = "session_ended";

    private final String baseUrl;
    private final String apiKey;
    private final String hmacSecret;
    private final String serverId;
    private final HttpClient http;
    private final ExecutorService executor;
    private volatile boolean linked = false;

    public ApiCoreClient(@Nonnull String baseUrl, @Nonnull String apiKey,
            @Nonnull String hmacSecret, @Nonnull String serverId) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.hmacSecret = hmacSecret;
        this.serverId = serverId;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HytaleServerManagement-ApiCore");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Tells whether the client holds enough configuration to talk to the core.
     */
    public boolean isConfigured() {
        return !baseUrl.isBlank()
                && !apiKey.isBlank()
                && !hmacSecret.isBlank()
                && !serverId.isBlank();
    }

    /**
     * Reports that a player session started.
     *
     * @param hytaleId the player UUID
     * @param joinedAt the instant the player entered the world
     */
    public void sessionStarted(@Nonnull UUID hytaleId, @Nonnull Instant joinedAt) {
        JsonObject body = new JsonObject();
        body.addProperty("event_type", SESSION_STARTED);
        body.addProperty("hytale_server_id", serverId);
        body.addProperty("hytale_id", hytaleId.toString());
        body.addProperty("joined_at", DateTimeFormatter.ISO_INSTANT.format(joinedAt));
        send("session_started", GSON.toJson(body));
    }

    /**
     * Reports that a player session ended.
     *
     * @param hytaleId the player UUID
     */
    public void sessionEnded(@Nonnull UUID hytaleId) {
        JsonObject body = new JsonObject();
        body.addProperty("event_type", SESSION_ENDED);
        body.addProperty("hytale_server_id", serverId);
        body.addProperty("hytale_id", hytaleId.toString());
        send("session_ended", GSON.toJson(body));
    }

    /**
     * Tells whether this server is currently linked to the core.
     */
    public boolean isLinked() {
        return linked;
    }

    /**
     * Links this server's agent to the core (ADR-0016), so the core can push
     * management commands back to the plugin. Runs on the caller thread and
     * returns whether the core accepted the registration.
     *
     * @param agentUrl   the loopback base URL of this plugin's REST API
     * @param agentToken the bearer token callers must present
     * @return {@code true} when the core registered the agent
     */
    public boolean link(@Nonnull String agentUrl, @Nonnull String agentToken) {
        if (!isConfigured() || agentUrl.isBlank() || agentToken.isBlank()) {
            LOGGER.at(Level.WARNING).log("Cannot link to api-core: client or agent parameters incomplete");
            return false;
        }
        JsonObject body = new JsonObject();
        body.addProperty("url", agentUrl);
        body.addProperty("token", agentToken);
        String payload = GSON.toJson(body);

        try {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/servers/" + serverId + "/agent"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", apiKey)
                    .header("X-Timestamp", timestamp)
                    .header("X-Signature", sign(timestamp, payload))
                    .PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            if (ok) {
                linked = true;
                LOGGER.at(Level.INFO).log("Linked agent with api-core (HTTP %d)", response.statusCode());
            } else {
                LOGGER.at(Level.WARNING).log("api-core rejected the link: HTTP %d %s",
                        response.statusCode(), response.body());
            }
            return ok;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            LOGGER.at(Level.WARNING).withCause(exception).log("Failed to link agent with api-core");
            return false;
        }
    }

    /**
     * Unlinks this server's agent from the core. Runs on the caller thread.
     *
     * @return {@code true} when the core accepted the unlink
     */
    public boolean unlink() {
        if (!isConfigured()) {
            return false;
        }
        try {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/servers/" + serverId + "/agent"))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-Api-Key", apiKey)
                    .header("X-Timestamp", timestamp)
                    .header("X-Signature", sign(timestamp, ""))
                    .DELETE()
                    .build();

            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            if (ok) {
                linked = false;
                LOGGER.at(Level.INFO).log("Unlinked agent from api-core (HTTP %d)", response.statusCode());
            } else {
                LOGGER.at(Level.WARNING).log("api-core rejected the unlink: HTTP %d %s",
                        response.statusCode(), response.body());
            }
            return ok;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            LOGGER.at(Level.WARNING).withCause(exception).log("Failed to unlink agent from api-core");
            return false;
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void send(@Nonnull String label, @Nonnull String body) {
        if (!isConfigured()) {
            LOGGER.at(Level.FINE).log("Skipping api-core %s event: client is not configured", label);
            return;
        }
        executor.execute(() -> {
            try {
                String timestamp = String.valueOf(Instant.now().getEpochSecond());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/webhook"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-Api-Key", apiKey)
                        .header("X-Timestamp", timestamp)
                        .header("X-Signature", sign(timestamp, body))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = http.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    LOGGER.at(Level.FINE).log("api-core accepted %s event (HTTP %d)",
                            label, response.statusCode());
                } else {
                    LOGGER.at(Level.WARNING).log("api-core rejected %s event: HTTP %d %s",
                            label, response.statusCode(), response.body());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                LOGGER.at(Level.WARNING).withCause(exception)
                        .log("Failed to deliver %s event to api-core", label);
            }
        });
    }

    @Nonnull
    private String sign(@Nonnull String timestamp, @Nonnull String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    @Nonnull
    private static String toHex(@Nonnull byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    @Nonnull
    private static String trimTrailingSlash(@Nonnull String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
