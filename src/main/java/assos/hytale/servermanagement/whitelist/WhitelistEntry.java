package assos.hytale.servermanagement.whitelist;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A single whitelist entry.
 *
 * @param uuid     the player UUID
 * @param username the resolved username, or {@code null} when it could not be
 *                 resolved (for example entries added via the native command)
 */
public record WhitelistEntry(@Nonnull UUID uuid, @Nullable String username) {

    public String uuidString() {
        return uuid.toString();
    }
}
