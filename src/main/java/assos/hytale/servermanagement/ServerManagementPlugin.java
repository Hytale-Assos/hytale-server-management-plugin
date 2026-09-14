package assos.hytale.servermanagement;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Entry point of the Hytale Server Management plugin.
 */
public class ServerManagementPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public ServerManagementPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        LOGGER.at(Level.INFO).log("Hytale Server Management setup complete");
    }

    @Override
    protected void shutdown() {
        LOGGER.at(Level.INFO).log("Hytale Server Management shut down");
    }
}
