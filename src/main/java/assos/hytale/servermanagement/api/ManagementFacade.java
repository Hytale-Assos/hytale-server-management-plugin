package assos.hytale.servermanagement.api;

import com.google.gson.JsonObject;

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
}
