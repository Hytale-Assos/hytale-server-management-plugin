package assos.hytale.servermanagement.whitelist;

import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.auth.ProfileServiceClient;
import com.hypixel.hytale.server.core.auth.ServerAuthManager;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves a player reference (online username, offline username, or UUID) into
 * a Hytale {@link ProfileServiceClient.PublicGameProfile}.
 *
 * <p>Resolution order mirrors the native {@code GAME_PROFILE_LOOKUP} argument
 * type: online players first, then a UUID literal, then the remote profile
 * service using the server session token.</p>
 */
public final class ProfileResolver {

    private static final Pattern UUID_PATTERN = Pattern
            .compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final PermissionsModule permissions;

    public ProfileResolver(@Nonnull PermissionsModule permissions) {
        this.permissions = permissions;
    }

    /**
     * Resolves an identifier to a player profile.
     *
     * @param identifier a username or a UUID string
     * @return the resolved profile, never {@code null}
     * @throws PlayerResolutionException when the identifier cannot be resolved
     */
    @Nonnull
    public ProfileServiceClient.PublicGameProfile resolve(@Nonnull String identifier) {
        String trimmed = identifier.trim();
        if (trimmed.isEmpty()) {
            throw new PlayerResolutionException("Player identifier cannot be empty");
        }

        ProfileServiceClient.PublicGameProfile online = findOnline(trimmed);
        if (online != null) {
            return online;
        }

        UUID uuid = tryParseUuid(trimmed);
        String sessionToken = requireSessionToken();
        ProfileServiceClient client = ServerAuthManager.getInstance().getProfileServiceClient();
        if (client == null) {
            throw new PlayerResolutionException("Profile service is not available on this server");
        }

        ProfileServiceClient.PublicGameProfile profile = lookup(client, uuid, trimmed, sessionToken);
        if (profile == null || profile.getUuid() == null) {
            throw new PlayerResolutionException("No Hytale profile found for '" + trimmed + "'");
        }
        return profile;
    }

    /**
     * Looks up a username among currently connected players.
     *
     * @param username the username to look up
     * @return the matching online player profile, or {@code null}
     */
    @Nullable
    public ProfileServiceClient.PublicGameProfile findOnline(@Nonnull String username) {
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        var players = universe.getPlayers();
        if (players.isEmpty()) {
            return null;
        }
        var player = NameMatching.DEFAULT.find(players, username, p -> p.getUsername());
        if (player == null) {
            return null;
        }
        return new ProfileServiceClient.PublicGameProfile(player.getUuid(), player.getUsername());
    }

    /**
     * Returns whether the given UUID currently has the native server join grant.
     *
     * @param uuid the player UUID
     * @return {@code true} when the player is whitelisted
     */
    public boolean isWhitelisted(@Nonnull UUID uuid) {
        return permissions.hasUserGrant(uuid, WhitelistService.JOIN_PERMISSION);
    }

    /**
     * Returns the set of UUIDs holding the native server join grant.
     *
     * @return an immutable snapshot of whitelisted UUIDs
     */
    @Nonnull
    public Set<UUID> listWhitelisted() {
        return permissions.getUsersWithPermission(WhitelistService.JOIN_PERMISSION);
    }

    @Nonnull
    private static UUID tryParseUuid(@Nonnull String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Nonnull
    private static String requireSessionToken() {
        ServerAuthManager authManager = ServerAuthManager.getInstance();
        if (authManager == null) {
            throw new PlayerResolutionException("Authentication manager is not available on this server");
        }
        String token = authManager.getSessionToken();
        if (token == null || token.isEmpty()) {
            throw new PlayerResolutionException(
                    "No session token available for profile lookup; start the server in online mode");
        }
        return token;
    }

    @Nullable
    private static ProfileServiceClient.PublicGameProfile lookup(@Nonnull ProfileServiceClient client,
            @Nullable UUID uuid, @Nonnull String username, @Nonnull String sessionToken) {
        try {
            if (uuid != null) {
                return client.getProfileByUuid(uuid, sessionToken);
            }
            return client.getProfileByUsername(username, sessionToken);
        } catch (RuntimeException exception) {
            throw new PlayerResolutionException("Profile lookup failed for '" + username + "'", exception);
        }
    }

    /**
     * Returns whether the supplied value is a syntactically valid UUID.
     *
     * @param value the value to test
     * @return {@code true} when the value parses as a UUID
     */
    public static boolean isUuid(@Nonnull String value) {
        return UUID_PATTERN.matcher(value).matches();
    }
}
