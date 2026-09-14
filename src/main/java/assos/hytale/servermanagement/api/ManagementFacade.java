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
}
