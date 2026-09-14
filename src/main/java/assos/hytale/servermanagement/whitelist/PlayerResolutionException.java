package assos.hytale.servermanagement.whitelist;

/**
 * Thrown when a player identifier (username or UUID) cannot be resolved to a
 * known Hytale profile.
 */
public class PlayerResolutionException extends RuntimeException {

    public PlayerResolutionException(String message) {
        super(message);
    }

    public PlayerResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
