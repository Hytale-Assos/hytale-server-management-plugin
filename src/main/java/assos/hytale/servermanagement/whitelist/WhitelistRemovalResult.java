package assos.hytale.servermanagement.whitelist;

import javax.annotation.Nonnull;

/**
 * Result of removing a player from the whitelist.
 *
 * @param entry        the removed entry
 * @param disconnected whether an online player with this UUID was disconnected
 */
public record WhitelistRemovalResult(@Nonnull WhitelistEntry entry, boolean disconnected) {
}
