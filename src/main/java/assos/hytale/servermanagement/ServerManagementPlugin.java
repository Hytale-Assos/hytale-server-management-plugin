package assos.hytale.servermanagement;

import assos.hytale.servermanagement.api.ApiServer;
import assos.hytale.servermanagement.api.Json;
import assos.hytale.servermanagement.api.ManagementFacade;
import assos.hytale.servermanagement.apicore.ApiCoreClient;
import assos.hytale.servermanagement.auth.AuthService;
import assos.hytale.servermanagement.config.ManagementConfig;
import assos.hytale.servermanagement.session.SessionTracker;
import assos.hytale.servermanagement.whitelist.WhitelistService;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.Config;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Entry point of the Hytale Server Management plugin.
 *
 * <p>The plugin is fully driven by its REST API: the native whitelist is exposed
 * over HTTP and no in-game command is registered. All administration happens
 * through the API.</p>
 */
public class ServerManagementPlugin extends JavaPlugin implements ManagementFacade {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private Config<ManagementConfig> config;
    private WhitelistService whitelistService;
    private ApiServer apiServer;
    private ApiCoreClient apiCoreClient;
    private SessionTracker sessionTracker;
    private final AuthService authService = new AuthService();
    private EventRegistration<String, PlayerReadyEvent> readyRegistration;
    private EventRegistration<Void, PlayerDisconnectEvent> disconnectRegistration;

    private static final String CONFIG_NAME = "management_config";

    public ServerManagementPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        this.config = this.withConfig(CONFIG_NAME, ManagementConfig.CODEC);
    }

    @Override
    protected void setup() {
        config.save();
        LOGGER.at(Level.INFO).log("Hytale Server Management setup complete");
    }

    @Override
    protected void start() {
        AccessControlModule accessControl = AccessControlModule.get();
        PermissionsModule permissions = PermissionsModule.get();
        if (accessControl == null || permissions == null) {
            LOGGER.at(Level.SEVERE).log(
                    "Native access control or permissions module is unavailable; whitelist management is disabled");
            return;
        }

        whitelistService = new WhitelistService(accessControl, permissions,
                () -> config.get().isDisconnectOnWhitelistRemoval());

        startApiCore();

        ManagementConfig managementConfig = config.get();
        if (!managementConfig.isApiEnabled()) {
            LOGGER.at(Level.SEVERE).log(
                    "Management REST API is disabled in configuration; there is no other way to manage the plugin");
            return;
        }

        ensureToken(managementConfig);
        startApi(managementConfig);
    }

    @Override
    protected void shutdown() {
        if (apiServer != null) {
            apiServer.stop();
            apiServer = null;
        }
        if (whitelistService != null) {
            whitelistService.shutdown();
            whitelistService = null;
        }
        unregisterSessionEvents();
        if (apiCoreClient != null) {
            apiCoreClient.shutdown();
            apiCoreClient = null;
        }
        sessionTracker = null;
        LOGGER.at(Level.INFO).log("Hytale Server Management shut down");
    }

    /**
     * Builds the api-core client and subscribes to the player lifecycle events.
     * No-op when api-core integration is disabled or not configured.
     */
    private void startApiCore() {
        ManagementConfig managementConfig = config.get();
        if (!managementConfig.isApiCoreEnabled()) {
            LOGGER.at(Level.INFO).log("api-core integration is disabled in configuration");
            return;
        }

        ApiCoreClient client = new ApiCoreClient(
                managementConfig.getApiCoreUrl(),
                managementConfig.getApiCoreApiKey(),
                managementConfig.getApiCoreHmacSecret(),
                managementConfig.getApiCoreServerId());
        if (!client.isConfigured()) {
            LOGGER.at(Level.WARNING).log(
                    "api-core integration is enabled but ApiCoreUrl/ApiCoreApiKey/ApiCoreHmacSecret/ApiCoreServerId "
                            + "are incomplete; session events are disabled");
            client.shutdown();
            return;
        }

        apiCoreClient = client;
        sessionTracker = new SessionTracker(client);
        readyRegistration = getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> {
            PlayerRef playerRef = event.getPlayerRef()
                    .getStore()
                    .getComponent(event.getPlayerRef(), PlayerRef.getComponentType());
            if (playerRef != null) {
                sessionTracker.onReady(playerRef);
            }
        });
        disconnectRegistration = getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,
                event -> sessionTracker.onDisconnect(event.getPlayerRef()));
        LOGGER.at(Level.INFO).log("api-core integration enabled; player sessions are reported");
    }

    /**
     * Reloads {@code management_config.json} from disk and restarts the REST API.
     *
     * <p>The config is re-read from the file, so edits made while the server runs
     * take effect.</p>
     */
    @Override
    public void reload() {
        if (apiServer != null) {
            apiServer.stop();
            apiServer = null;
        }
        unregisterSessionEvents();
        if (apiCoreClient != null) {
            apiCoreClient.shutdown();
            apiCoreClient = null;
        }
        sessionTracker = null;

        Config<ManagementConfig> reloaded = new Config<>(getDataDirectory(), CONFIG_NAME, ManagementConfig.CODEC);
        try {
            reloaded.load().join();
            this.config = reloaded;
        } catch (RuntimeException exception) {
            LOGGER.at(Level.SEVERE).withCause(exception)
                    .log("Failed to reload management_config.json; keeping the previous configuration");
        }

        startApiCore();

        ManagementConfig managementConfig = config.get();
        if (managementConfig.isApiEnabled() && whitelistService != null) {
            ensureToken(managementConfig);
            startApi(managementConfig);
        } else if (!managementConfig.isApiEnabled()) {
            LOGGER.at(Level.INFO).log("Management REST API is disabled in configuration");
        }
    }

    @Override
    public JsonObject buildLinkStatus() {
        ManagementConfig managementConfig = config.get();
        JsonObject root = Json.success();
        root.addProperty("apiCoreEnabled", managementConfig.isApiCoreEnabled());
        root.addProperty("apiCoreUrl", managementConfig.getApiCoreUrl());
        root.addProperty("serverId", managementConfig.getApiCoreServerId());
        boolean available = apiCoreClient != null;
        root.addProperty("available", available);
        root.addProperty("linked", available && apiCoreClient.isLinked());
        return root;
    }

    /**
     * Links this server's agent to api-core on demand (ADR-0016). The link is
     * explicit: nothing is registered at startup.
     *
     * @return the public status document after the attempt
     */
    @Override
    public JsonObject linkApiCore() {
        ManagementConfig managementConfig = config.get();
        JsonObject root = Json.success();
        if (apiCoreClient == null || apiServer == null || !managementConfig.isApiEnabled()) {
            root.addProperty("linked", false);
            root.addProperty("message",
                    "api-core integration or management REST API is not active");
            return root;
        }
        String host = resolveManagementHost(managementConfig);
        String agentUrl = "http://" + host + ":" + managementConfig.getApiPort();
        String token = managementConfig.isApiRequireAuth() ? managementConfig.getApiToken() : "";
        boolean ok = apiCoreClient.link(agentUrl, token);
        root.addProperty("linked", ok);
        root.addProperty("agentUrl", agentUrl);
        return root;
    }

    /**
     * Unlinks this server's agent from api-core on demand.
     *
     * @return the public status document after the attempt
     */
    @Override
    public JsonObject unlinkApiCore() {
        JsonObject root = Json.success();
        if (apiCoreClient == null) {
            root.addProperty("linked", false);
            root.addProperty("message", "api-core integration is not active");
            return root;
        }
        boolean ok = apiCoreClient.unlink();
        root.addProperty("linked", false);
        root.addProperty("accepted", ok);
        return root;
    }

    @Override
    public JsonObject buildAuthStatus() {
        return authService.status();
    }

    @Override
    public JsonObject startAuthDeviceFlow() {
        return authService.startDeviceFlow();
    }

    @Override
    public JsonObject selectAuthProfile(@Nullable String username, @Nullable Integer index) {
        return authService.selectProfile(username, index);
    }

    @Override
    public JsonObject logoutAuth() {
        return authService.logout();
    }

    @Nonnull
    private static String resolveManagementHost(@Nonnull ManagementConfig managementConfig) {
        String host = managementConfig.getApiHost();
        if (host == null || host.isBlank()) {
            return "127.0.0.1";
        }
        if (!"127.0.0.1".equals(host) && !managementConfig.isAllowRemoteManagement()) {
            return "127.0.0.1";
        }
        return host;
    }

    private void unregisterSessionEvents() {
        if (readyRegistration != null) {
            readyRegistration.unregister();
            readyRegistration = null;
        }
        if (disconnectRegistration != null) {
            disconnectRegistration.unregister();
            disconnectRegistration = null;
        }
    }

    private void startApi(@Nonnull ManagementConfig managementConfig) {
        apiServer = new ApiServer(managementConfig, whitelistService, this);
        try {
            apiServer.start();
        } catch (Exception exception) {
            LOGGER.at(Level.SEVERE).withCause(exception).log("Failed to start the management REST API");
            apiServer = null;
        }
    }

    private void ensureToken(@Nonnull ManagementConfig managementConfig) {
        if (!managementConfig.isApiRequireAuth()) {
            return;
        }
        if (managementConfig.getApiToken() == null || managementConfig.getApiToken().isBlank()) {
            String token = ManagementConfig.generateToken();
            managementConfig.setApiToken(token);
            config.save();
            LOGGER.at(Level.WARNING).log(
                    "No API token was configured; generated one. Set the 'Authorization: Bearer <token>' header "
                            + "using the value stored in management_config.json");
        }
    }

    public boolean isApiRunning() {
        return apiServer != null;
    }

    @Override
    public JsonObject buildStatus() {
        ManagementConfig managementConfig = config.get();
        JsonObject root = Json.success();
        root.addProperty("plugin", getIdentifier().toString());
        root.addProperty("version", getManifest().getVersion().toString());
        root.addProperty("apiRunning", isApiRunning());
        root.addProperty("apiEnabled", managementConfig.isApiEnabled());
        root.addProperty("apiAddress", managementConfig.getApiHost() + ":" + managementConfig.getApiPort());
        root.addProperty("apiAuthRequired", managementConfig.isApiRequireAuth());
        root.addProperty("allowRemoteManagement", managementConfig.isAllowRemoteManagement());
        root.addProperty("logRequests", managementConfig.isLogRequests());
        root.addProperty("apiCoreLinked", apiCoreClient != null && apiCoreClient.isLinked());

        JsonObject whitelist = Json.object();
        boolean serviceAvailable = whitelistService != null;
        whitelist.addProperty("available", serviceAvailable);
        whitelist.addProperty("enabled", serviceAvailable && whitelistService.isEnabled());
        whitelist.addProperty("count", serviceAvailable ? whitelistService.list().size() : 0);
        root.add("whitelist", whitelist);

        HytaleServer server = HytaleServer.get();
        JsonObject serverInfo = Json.object();
        serverInfo.addProperty("booted", server != null && server.isBooted());
        serverInfo.addProperty("name", server != null ? server.getServerName() : null);
        serverInfo.addProperty("bootTime", server != null && server.getBoot() != null
                ? server.getBoot().toString()
                : null);
        root.add("server", serverInfo);
        return root;
    }

    @Nonnull
    public Config<ManagementConfig> getConfig() {
        return config;
    }

    @Nonnull
    public WhitelistService getWhitelistService() {
        if (whitelistService == null) {
            throw new IllegalStateException("Whitelist service is not available yet");
        }
        return whitelistService;
    }
}
