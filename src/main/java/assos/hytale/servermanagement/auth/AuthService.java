package assos.hytale.servermanagement.auth;

import assos.hytale.servermanagement.api.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.auth.ServerAuthManager;
import com.hypixel.hytale.server.core.auth.SessionServiceClient;
import com.hypixel.hytale.server.core.auth.oauth.OAuthDeviceFlow;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Drives Hytale account authentication from the REST API, so a headless server
 * hosted in a container can be linked without an interactive console.
 *
 * <p>Only the OAuth device flow is started: it yields a user code and a
 * verification URL the operator enters from any device. No TTY, browser or
 * stdin is required on the server host.</p>
 *
 * <p>{@link ServerAuthManager} is not documented for plugins and may change
 * between server versions; all its usage is confined to this class.</p>
 */
public class AuthService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final AtomicReference<DeviceInfo> deviceInfo = new AtomicReference<>();
    private volatile boolean flowActive = false;
    private volatile String lastError;
    private volatile ServerAuthManager.AuthResult lastResult;

    private record DeviceInfo(@Nonnull String userCode, @Nullable String verificationUri,
            @Nullable String verificationUriComplete, int expiresIn) {
    }

    /**
     * Reports whether the server currently holds an account session token.
     *
     * @return {@code true} when the server is authenticated
     */
    public boolean isAuthenticated() {
        ServerAuthManager auth = ServerAuthManager.getInstance();
        return auth != null && auth.hasSessionToken();
    }

    /**
     * Builds the authentication status document, including any in-flight device
     * flow information and pending profiles.
     *
     * @return the status document
     */
    @Nonnull
    public JsonObject status() {
        ServerAuthManager auth = ServerAuthManager.getInstance();
        JsonObject root = Json.success();
        if (auth == null) {
            root.addProperty("available", false);
            return root;
        }
        root.addProperty("available", true);
        root.addProperty("authenticated", auth.hasSessionToken());
        root.addProperty("singleplayer", auth.isSingleplayer());
        root.addProperty("hasSessionToken", auth.hasSessionToken());
        root.addProperty("hasIdentityToken", auth.hasIdentityToken());
        root.addProperty("authMode", String.valueOf(auth.getAuthMode()));
        root.addProperty("authStatus", auth.getAuthStatus());
        Instant expiry = auth.getTokenExpiry();
        if (expiry != null) {
            root.addProperty("tokenExpiry", expiry.toString());
        }
        JsonObject selected = profileToJson(auth.getSelectedProfile());
        if (selected != null) {
            root.add("selectedProfile", selected);
        }
        root.addProperty("flowActive", flowActive);
        attachDevice(root);
        if (lastError != null) {
            root.addProperty("lastError", lastError);
        }
        if (lastResult != null) {
            root.addProperty("lastResult", lastResult.name());
        }

        JsonArray pending = new JsonArray();
        SessionServiceClient.GameProfile[] profiles = auth.getPendingProfiles();
        if (profiles != null) {
            for (SessionServiceClient.GameProfile profile : profiles) {
                JsonObject object = profileToJson(profile);
                if (object != null) {
                    pending.add(object);
                }
            }
        }
        root.addProperty("pendingProfileCount", pending.size());
        root.add("pendingProfiles", pending);
        return root;
    }

    /**
     * Starts the OAuth device flow when the server is not authenticated yet.
     * Idempotent while a flow is already in progress.
     *
     * @return the result document, with the device code as soon as it is known
     */
    @Nonnull
    public synchronized JsonObject startDeviceFlow() {
        JsonObject root = Json.success();
        ServerAuthManager auth = ServerAuthManager.getInstance();
        if (auth == null) {
            root.addProperty("started", false);
            root.addProperty("message", "Authentication manager is not available on this server");
            return root;
        }
        if (auth.isSingleplayer()) {
            root.addProperty("started", false);
            root.addProperty("message", "Device login is not available in singleplayer");
            return root;
        }
        if (auth.hasSessionToken() && auth.hasIdentityToken()) {
            root.addProperty("started", false);
            root.addProperty("message", "Server is already authenticated");
            return root;
        }
        if (flowActive) {
            root.addProperty("started", false);
            root.addProperty("message", "A device authorization flow is already in progress");
            attachDevice(root);
            return root;
        }

        deviceInfo.set(null);
        lastError = null;
        lastResult = null;
        flowActive = true;

        OAuthDeviceFlow flow = new OAuthDeviceFlow() {
            @Override
            public void onFlowInfo(String userCode, String verificationUri, String verificationUriComplete,
                    int expiresIn) {
                deviceInfo.set(new DeviceInfo(userCode, verificationUri, verificationUriComplete, expiresIn));
                LOGGER.at(Level.INFO).log(
                        "Device authorization started: enter code %s at %s (expires in %d seconds)",
                        userCode, verificationUri, expiresIn);
            }
        };

        CompletableFuture<ServerAuthManager.AuthResult> future;
        try {
            future = auth.startFlowAsync(flow);
        } catch (RuntimeException exception) {
            flowActive = false;
            lastError = exception.getMessage();
            LOGGER.at(Level.WARNING).withCause(exception).log("Failed to start the device authorization flow");
            root.addProperty("started", false);
            root.addProperty("message", "Failed to start the device authorization flow");
            return root;
        }
        if (future == null) {
            flowActive = false;
            root.addProperty("started", false);
            root.addProperty("message", "The authentication flow could not be started");
            return root;
        }
        future.whenComplete((result, error) -> handleFlowCompletion(result, error));

        root.addProperty("started", true);
        root.addProperty("message", "Device authorization started; poll GET /api/v1/auth/status for the code");
        attachDevice(root);
        return root;
    }

    /**
     * Completes an authentication that is waiting for a profile selection.
     *
     * @param username the profile username, or {@code null}
     * @param index    the zero-based pending profile index, or {@code null}
     * @return the result document
     */
    @Nonnull
    public synchronized JsonObject selectProfile(@Nullable String username, @Nullable Integer index) {
        ServerAuthManager auth = ServerAuthManager.getInstance();
        if (auth == null) {
            JsonObject root = Json.success();
            root.addProperty("selected", false);
            root.addProperty("message", "Authentication manager is not available on this server");
            return root;
        }
        if (username == null && index == null) {
            throw new IllegalArgumentException("Provide 'profile' (username) or 'index'");
        }
        boolean selected = username != null
                ? auth.selectPendingProfileByUsername(username)
                : auth.selectPendingProfile(index);
        JsonObject root = Json.success();
        root.addProperty("selected", selected);
        if (!selected) {
            root.addProperty("message", "No matching pending profile; check GET /api/v1/auth/status");
        }
        return root;
    }

    /**
     * Logs the server out and clears any cached flow state.
     *
     * @return the result document
     */
    @Nonnull
    public synchronized JsonObject logout() {
        ServerAuthManager auth = ServerAuthManager.getInstance();
        JsonObject root = Json.success();
        if (auth == null) {
            root.addProperty("loggedOut", false);
            root.addProperty("message", "Authentication manager is not available on this server");
            return root;
        }
        auth.logout();
        deviceInfo.set(null);
        lastError = null;
        lastResult = null;
        root.addProperty("loggedOut", true);
        return root;
    }

    private void handleFlowCompletion(@Nullable ServerAuthManager.AuthResult result, @Nullable Throwable error) {
        flowActive = false;
        if (error != null) {
            lastError = error.getMessage();
            deviceInfo.set(null);
            LOGGER.at(Level.WARNING).withCause(error).log("Device authorization flow failed");
            return;
        }
        lastResult = result;
        if (result == ServerAuthManager.AuthResult.SUCCESS) {
            deviceInfo.set(null);
            lastError = null;
            LOGGER.at(Level.INFO).log("Server authentication succeeded");
        } else if (result == ServerAuthManager.AuthResult.PENDING_PROFILE_SELECTION) {
            LOGGER.at(Level.INFO)
                    .log("Authentication pending profile selection; use POST /api/v1/auth/profile");
        } else {
            lastError = "Authentication failed";
            deviceInfo.set(null);
            LOGGER.at(Level.WARNING).log("Server authentication failed");
        }
    }

    private void attachDevice(@Nonnull JsonObject root) {
        DeviceInfo info = deviceInfo.get();
        if (info == null) {
            return;
        }
        JsonObject device = new JsonObject();
        device.addProperty("userCode", info.userCode());
        if (info.verificationUri() != null) {
            device.addProperty("verificationUri", info.verificationUri());
        }
        if (info.verificationUriComplete() != null) {
            device.addProperty("verificationUriComplete", info.verificationUriComplete());
        }
        device.addProperty("expiresIn", info.expiresIn());
        root.add("device", device);
    }

    @Nullable
    private static JsonObject profileToJson(@Nullable SessionServiceClient.GameProfile profile) {
        if (profile == null || profile.uuid == null) {
            return null;
        }
        JsonObject object = new JsonObject();
        object.addProperty("uuid", profile.uuid.toString());
        object.addProperty("username", profile.username);
        return object;
    }
}
