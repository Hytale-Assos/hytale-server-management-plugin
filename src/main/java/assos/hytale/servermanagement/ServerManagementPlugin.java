package assos.hytale.servermanagement;

import assos.hytale.servermanagement.api.ApiServer;
import assos.hytale.servermanagement.api.Json;
import assos.hytale.servermanagement.api.ManagementFacade;
import assos.hytale.servermanagement.config.ManagementConfig;
import assos.hytale.servermanagement.whitelist.WhitelistService;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;

import javax.annotation.Nonnull;
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

        whitelistService = new WhitelistService(accessControl, permissions);

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
        LOGGER.at(Level.INFO).log("Hytale Server Management shut down");
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

        Config<ManagementConfig> reloaded = new Config<>(getDataDirectory(), CONFIG_NAME, ManagementConfig.CODEC);
        try {
            reloaded.load().join();
            this.config = reloaded;
        } catch (RuntimeException exception) {
            LOGGER.at(Level.SEVERE).withCause(exception)
                    .log("Failed to reload management_config.json; keeping the previous configuration");
        }

        ManagementConfig managementConfig = config.get();
        if (managementConfig.isApiEnabled() && whitelistService != null) {
            ensureToken(managementConfig);
            startApi(managementConfig);
        } else if (!managementConfig.isApiEnabled()) {
            LOGGER.at(Level.INFO).log("Management REST API is disabled in configuration");
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
