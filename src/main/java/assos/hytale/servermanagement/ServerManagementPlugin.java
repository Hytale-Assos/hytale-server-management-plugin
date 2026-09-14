package assos.hytale.servermanagement;

import assos.hytale.servermanagement.config.ManagementConfig;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.Config;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Entry point of the Hytale Server Management plugin.
 */
public class ServerManagementPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String CONFIG_NAME = "management_config";

    private Config<ManagementConfig> config;

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
    protected void shutdown() {
        LOGGER.at(Level.INFO).log("Hytale Server Management shut down");
    }

    @Nonnull
    public Config<ManagementConfig> getConfig() {
        return config;
    }
}
