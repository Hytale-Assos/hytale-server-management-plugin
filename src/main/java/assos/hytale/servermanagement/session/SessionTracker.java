package assos.hytale.servermanagement.session;

import assos.hytale.servermanagement.apicore.ApiCoreClient;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Turns Hytale player lifecycle events into central {@code api-core} session
 * events.
 *
 * <p>{@code PlayerReadyEvent} can fire several times for the same player, so a
 * session is only started once per player until the matching disconnect.
 * {@code PlayerDisconnectEvent} may arrive without a ready event (failed
 * connection): the core tolerates a disconnect with no open session.</p>
 */
public class SessionTracker {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ApiCoreClient client;
    private final Map<UUID, Instant> openSessions = new ConcurrentHashMap<>();

    public SessionTracker(@Nonnull ApiCoreClient client) {
        this.client = client;
    }

    /**
     * Records the player entering the world and pushes {@code session_started}.
     *
     * @param playerRef the player handle
     */
    public void onReady(@Nonnull PlayerRef playerRef) {
        if (!playerRef.isValid()) {
            return;
        }
        UUID uuid = playerRef.getUuid();
        Instant joinedAt = Instant.now();
        Instant previous = openSessions.putIfAbsent(uuid, joinedAt);
        if (previous != null) {
            LOGGER.at(Level.FINE).log("Session already open for %s; ignoring duplicate ready", uuid);
            return;
        }
        LOGGER.at(Level.INFO).log("Player %s ready; reporting session start to api-core", uuid);
        client.sessionStarted(uuid, joinedAt);
    }

    /**
     * Records the player leaving and pushes {@code session_ended}.
     *
     * @param playerRef the player handle
     */
    public void onDisconnect(@Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        openSessions.remove(uuid);
        LOGGER.at(Level.INFO).log("Player %s disconnected; reporting session end to api-core", uuid);
        client.sessionEnded(uuid);
    }
}
