package assos.hytale.servermanagement.api;

import com.google.gson.JsonObject;

import javax.annotation.Nullable;

/**
 * Supplies plugin status information and lifecycle actions to the REST API,
 * keeping the HTTP layer decoupled from the plugin class.
 */
public interface ManagementFacade {

    /**
     * Builds the JSON payload returned by {@code GET /api/v1/status}.
     *
     * @return the status document
     */
    JsonObject buildStatus();

    /**
     * Reloads the plugin runtime using the current configuration.
     */
    void reload();

    /**
     * Current link status with the central api-core.
     *
     * @return the link status document
     */
    JsonObject buildLinkStatus();

    /**
     * Links this server's agent to the central api-core on demand.
     *
     * @return the resulting status document
     */
    JsonObject linkApiCore();

    /**
     * Unlinks this server's agent from the central api-core on demand.
     *
     * @return the resulting status document
     */
    JsonObject unlinkApiCore();

    /**
     * Authentication status of the server, including any in-flight device flow.
     *
     * @return the authentication status document
     */
    JsonObject buildAuthStatus();

    /**
     * Starts the OAuth device flow so a headless server can be authenticated
     * without an interactive console.
     *
     * @return the result document, with the device code as soon as it is known
     */
    JsonObject startAuthDeviceFlow();

    /**
     * Selects a pending game profile to finish an authentication.
     *
     * @param username the profile username, or {@code null}
     * @param index    the zero-based pending profile index, or {@code null}
     * @return the result document
     */
    JsonObject selectAuthProfile(@Nullable String username, @Nullable Integer index);

    /**
     * Logs the server out from its Hytale account.
     *
     * @return the result document
     */
    JsonObject logoutAuth();
}
