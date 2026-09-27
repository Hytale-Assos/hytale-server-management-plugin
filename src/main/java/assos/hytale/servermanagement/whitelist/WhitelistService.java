package assos.hytale.servermanagement.whitelist;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.auth.ProfileServiceClient;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Service layer around Hytale's native whitelist.
 *
 * <p>The native implementation stores the whitelist as the
 * {@code hytale.server.join} permission grant and toggles enforcement through
 * {@link AccessControlModule#setJoinPermissionRequired(boolean)}. All mutating
 * calls are serialized on a single-thread executor because the underlying
 * permission provider is not thread-safe and the plugin's REST API is served
 * from its own HTTP threads.</p>
 */
public class WhitelistService {

    /** Native permission id that doubles as the whitelist entry. */
    public static final String JOIN_PERMISSION = "hytale.server.join";

    private final AccessControlModule accessControl;
    private final PermissionsModule permissions;
    private final ProfileResolver profiles;
    private final ExecutorService executor;
    private final BooleanSupplier disconnectOnRemoval;

    public WhitelistService(@Nonnull AccessControlModule accessControl,
            @Nonnull PermissionsModule permissions,
            @Nonnull BooleanSupplier disconnectOnRemoval) {
        this.accessControl = accessControl;
        this.permissions = permissions;
        this.profiles = new ProfileResolver(permissions);
        this.disconnectOnRemoval = disconnectOnRemoval;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HytaleServerManagement-Whitelist");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean isEnabled() {
        return accessControl.isJoinPermissionRequired();
    }

    public CompletableFuture<Boolean> setEnabled(boolean enabled) {
        if (accessControl.isJoinPermissionRequired() == enabled) {
            return CompletableFuture.completedFuture(false);
        }
        return accessControl.setJoinPermissionRequired(enabled).thenApply(ignored -> true);
    }

    public boolean isWhitelisted(@Nonnull UUID uuid) {
        return profiles.isWhitelisted(uuid);
    }

    @Nonnull
    public List<WhitelistEntry> list() {
        Set<UUID> uuids = profiles.listWhitelisted();
        List<WhitelistEntry> entries = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) {
            entries.add(new WhitelistEntry(uuid, null));
        }
        return entries;
    }

    /**
     * Adds a player to the whitelist. Idempotent: adding an already whitelisted
     * player is a no-op and succeeds, so core-driven retries are safe.
     */
    @Nonnull
    public CompletableFuture<WhitelistEntry> add(@Nonnull String identifier) {
        return supplyAsync(() -> {
            ProfileServiceClient.PublicGameProfile profile = profiles.resolve(identifier);
            UUID uuid = profile.getUuid();
            if (profiles.isWhitelisted(uuid)) {
                return new WhitelistEntry(uuid, profile.getUsername());
            }
            accessControl.allowJoin(uuid);
            return new WhitelistEntry(uuid, profile.getUsername());
        });
    }

    /**
     * Removes a player from the whitelist. Idempotent: removing a player who is
     * not whitelisted is a no-op and succeeds, so core-driven retries are safe.
     */
    @Nonnull
    public CompletableFuture<WhitelistRemovalResult> remove(@Nonnull String identifier) {
        return supplyAsync(() -> {
            ProfileServiceClient.PublicGameProfile profile = profiles.resolve(identifier);
            UUID uuid = profile.getUuid();
            if (!profiles.isWhitelisted(uuid)) {
                return new WhitelistRemovalResult(new WhitelistEntry(uuid, profile.getUsername()), false);
            }
            accessControl.disallowJoin(uuid);

            boolean disconnected = false;
            if (disconnectOnRemoval.getAsBoolean()) {
                disconnected = disconnectIfOnline(uuid);
            }
            return new WhitelistRemovalResult(new WhitelistEntry(uuid, profile.getUsername()), disconnected);
        });
    }

    @Nonnull
    public CompletableFuture<WhitelistClearResult> clear() {
        return supplyAsync(() -> {
            int removed = accessControl.disallowAllJoins();
            int disconnected = 0;
            if (disconnectOnRemoval.getAsBoolean()) {
                disconnected = disconnectAllPlayers();
            }
            return new WhitelistClearResult(removed, disconnected);
        });
    }

    @Nonnull
    public ProfileResolver getProfileResolver() {
        return profiles;
    }

    /**
     * Disconnects an online player if they no longer have permission to join.
     *
     * @param uuid the player UUID
     * @return {@code true} when an online player was disconnected
     */
    private boolean disconnectIfOnline(@Nonnull UUID uuid) {
        Universe universe = Universe.get();
        if (universe == null) {
            return false;
        }
        PlayerRef player = universe.getPlayer(uuid);
        if (player == null || !player.isValid()) {
            return false;
        }
        if (accessControl.isAllowedToJoin(uuid)) {
            return false;
        }
        PacketHandler connection = player.getPacketHandler();
        if (connection == null) {
            return false;
        }
        connection.disconnect(Message.raw("You have been removed from the server whitelist."));
        return true;
    }

    /**
     * Disconnects every online player that no longer has permission to join.
     * Players keeping the permission through a group are left connected.
     *
     * @return the number of players disconnected
     */
    private int disconnectAllPlayers() {
        Universe universe = Universe.get();
        if (universe == null) {
            return 0;
        }
        int count = 0;
        for (PlayerRef player : List.copyOf(universe.getPlayers())) {
            if (player == null || !player.isValid()) {
                continue;
            }
            UUID uuid = player.getUuid();
            if (accessControl.isAllowedToJoin(uuid)) {
                continue;
            }
            PacketHandler connection = player.getPacketHandler();
            if (connection == null) {
                continue;
            }
            connection.disconnect(Message.raw("You have been removed from the server whitelist."));
            count++;
        }
        return count;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    @Nonnull
    private <T> CompletableFuture<T> supplyAsync(@Nonnull java.util.concurrent.Callable<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, executor);
    }

    @Nonnull
    private static String display(@Nonnull ProfileServiceClient.PublicGameProfile profile) {
        String username = profile.getUsername();
        if (username != null && !username.isEmpty()) {
            return username;
        }
        return String.valueOf(profile.getUuid());
    }
}
