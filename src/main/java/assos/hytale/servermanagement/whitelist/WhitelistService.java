package assos.hytale.servermanagement.whitelist;

import com.hypixel.hytale.server.core.auth.ProfileServiceClient;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    public WhitelistService(@Nonnull AccessControlModule accessControl,
            @Nonnull PermissionsModule permissions) {
        this.accessControl = accessControl;
        this.permissions = permissions;
        this.profiles = new ProfileResolver(permissions);
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

    @Nonnull
    public CompletableFuture<WhitelistEntry> add(@Nonnull String identifier) {
        return supplyAsync(() -> {
            ProfileServiceClient.PublicGameProfile profile = profiles.resolve(identifier);
            UUID uuid = profile.getUuid();
            if (profiles.isWhitelisted(uuid)) {
                throw new PlayerResolutionException(
                        "'" + display(profile) + "' is already whitelisted");
            }
            accessControl.allowJoin(uuid);
            return new WhitelistEntry(uuid, profile.getUsername());
        });
    }

    @Nonnull
    public CompletableFuture<WhitelistEntry> remove(@Nonnull String identifier) {
        return supplyAsync(() -> {
            ProfileServiceClient.PublicGameProfile profile = profiles.resolve(identifier);
            UUID uuid = profile.getUuid();
            if (!profiles.isWhitelisted(uuid)) {
                throw new PlayerResolutionException(
                        "'" + display(profile) + "' is not whitelisted");
            }
            accessControl.disallowJoin(uuid);
            return new WhitelistEntry(uuid, profile.getUsername());
        });
    }

    @Nonnull
    public CompletableFuture<Integer> clear() {
        return supplyAsync(accessControl::disallowAllJoins);
    }

    @Nonnull
    public ProfileResolver getProfileResolver() {
        return profiles;
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
