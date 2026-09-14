package assos.hytale.servermanagement.whitelist;

/**
 * Result of clearing the whole whitelist.
 *
 * @param removed      the number of entries removed
 * @param disconnected the number of online players disconnected
 */
public record WhitelistClearResult(int removed, int disconnected) {
}
