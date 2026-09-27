package assos.hytale.servermanagement.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Root configuration for the server management plugin.
 *
 * <p>The file is written to the plugin data directory as {@code management_config.json}.</p>
 */
public class ManagementConfig {

    public static final BuilderCodec<ManagementConfig> CODEC = BuilderCodec
            .builder(ManagementConfig.class, ManagementConfig::new)
            .append(new KeyedCodec<>("ApiEnabled", Codec.BOOLEAN),
                    (config, value, info) -> config.apiEnabled = value,
                    (config, info) -> config.apiEnabled)
            .add()
            .append(new KeyedCodec<>("ApiHost", Codec.STRING),
                    (config, value, info) -> config.apiHost = value,
                    (config, info) -> config.apiHost)
            .add()
            .append(new KeyedCodec<>("ApiPort", Codec.INTEGER),
                    (config, value, info) -> config.apiPort = value,
                    (config, info) -> config.apiPort)
            .add()
            .append(new KeyedCodec<>("ApiToken", Codec.STRING),
                    (config, value, info) -> config.apiToken = value,
                    (config, info) -> config.apiToken)
            .add()
            .append(new KeyedCodec<>("ApiRequireAuth", Codec.BOOLEAN),
                    (config, value, info) -> config.apiRequireAuth = value,
                    (config, info) -> config.apiRequireAuth)
            .add()
            .append(new KeyedCodec<>("AllowRemoteManagement", Codec.BOOLEAN),
                    (config, value, info) -> config.allowRemoteManagement = value,
                    (config, info) -> config.allowRemoteManagement)
            .add()
            .append(new KeyedCodec<>("LogRequests", Codec.BOOLEAN),
                    (config, value, info) -> config.logRequests = value,
                    (config, info) -> config.logRequests)
            .add()
            .append(new KeyedCodec<>("DisconnectOnWhitelistRemoval", Codec.BOOLEAN),
                    (config, value, info) -> config.disconnectOnWhitelistRemoval = value,
                    (config, info) -> config.disconnectOnWhitelistRemoval)
            .add()
            .append(new KeyedCodec<>("ApiCoreEnabled", Codec.BOOLEAN),
                    (config, value, info) -> config.apiCoreEnabled = value,
                    (config, info) -> config.apiCoreEnabled)
            .add()
            .append(new KeyedCodec<>("ApiCoreUrl", Codec.STRING),
                    (config, value, info) -> config.apiCoreUrl = value,
                    (config, info) -> config.apiCoreUrl)
            .add()
            .append(new KeyedCodec<>("ApiCoreApiKey", Codec.STRING),
                    (config, value, info) -> config.apiCoreApiKey = value,
                    (config, info) -> config.apiCoreApiKey)
            .add()
            .append(new KeyedCodec<>("ApiCoreHmacSecret", Codec.STRING),
                    (config, value, info) -> config.apiCoreHmacSecret = value,
                    (config, info) -> config.apiCoreHmacSecret)
            .add()
            .append(new KeyedCodec<>("ApiCoreServerId", Codec.STRING),
                    (config, value, info) -> config.apiCoreServerId = value,
                    (config, info) -> config.apiCoreServerId)
            .add()
            .build();

    private boolean apiEnabled = true;
    private String apiHost = "127.0.0.1";
    private int apiPort = 8080;
    private String apiToken = "";
    private boolean apiRequireAuth = true;
    private boolean allowRemoteManagement = false;
    private boolean logRequests = true;
    private boolean disconnectOnWhitelistRemoval = true;
    private boolean apiCoreEnabled = false;
    private String apiCoreUrl = "http://127.0.0.1:3000";
    private String apiCoreApiKey = "";
    private String apiCoreHmacSecret = "";
    private String apiCoreServerId = "";

    private ManagementConfig() {
    }

    /**
     * Generates a random bearer token for the REST API.
     *
     * @return a URL-safe random token
     */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public boolean isApiEnabled() {
        return apiEnabled;
    }

    public void setApiEnabled(boolean apiEnabled) {
        this.apiEnabled = apiEnabled;
    }

    public String getApiHost() {
        return apiHost;
    }

    public void setApiHost(String apiHost) {
        this.apiHost = apiHost;
    }

    public int getApiPort() {
        return apiPort;
    }

    public void setApiPort(int apiPort) {
        this.apiPort = apiPort;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public boolean isApiRequireAuth() {
        return apiRequireAuth;
    }

    public void setApiRequireAuth(boolean apiRequireAuth) {
        this.apiRequireAuth = apiRequireAuth;
    }

    public boolean isAllowRemoteManagement() {
        return allowRemoteManagement;
    }

    public void setAllowRemoteManagement(boolean allowRemoteManagement) {
        this.allowRemoteManagement = allowRemoteManagement;
    }

    public boolean isLogRequests() {
        return logRequests;
    }

    public void setLogRequests(boolean logRequests) {
        this.logRequests = logRequests;
    }

    public boolean isDisconnectOnWhitelistRemoval() {
        return disconnectOnWhitelistRemoval;
    }

    public void setDisconnectOnWhitelistRemoval(boolean disconnectOnWhitelistRemoval) {
        this.disconnectOnWhitelistRemoval = disconnectOnWhitelistRemoval;
    }

    public boolean isApiCoreEnabled() {
        return apiCoreEnabled;
    }

    public void setApiCoreEnabled(boolean apiCoreEnabled) {
        this.apiCoreEnabled = apiCoreEnabled;
    }

    public String getApiCoreUrl() {
        return apiCoreUrl;
    }

    public void setApiCoreUrl(String apiCoreUrl) {
        this.apiCoreUrl = apiCoreUrl;
    }

    public String getApiCoreApiKey() {
        return apiCoreApiKey;
    }

    public void setApiCoreApiKey(String apiCoreApiKey) {
        this.apiCoreApiKey = apiCoreApiKey;
    }

    public String getApiCoreHmacSecret() {
        return apiCoreHmacSecret;
    }

    public void setApiCoreHmacSecret(String apiCoreHmacSecret) {
        this.apiCoreHmacSecret = apiCoreHmacSecret;
    }

    public String getApiCoreServerId() {
        return apiCoreServerId;
    }

    public void setApiCoreServerId(String apiCoreServerId) {
        this.apiCoreServerId = apiCoreServerId;
    }
}
