# Rapport — Données et actions disponibles sur un joueur (Hytale 0.6.5)

Ce document recense ce qu'un plugin serveur peut **observer** et **faire** sur un joueur
via l'API Hytale (`com.hypixel.hytale.server.core`). Il sert de référence pour les
prochaines fonctionnalités du plugin (modération, statistiques, etc.).

> Toutes les classes citées proviennent du serveur Hytale. Aucune API de "profil joueur"
> clé-en-main n'existe pour l'historique : les informations temporelles doivent être
> **collectées par le plugin** via les événements.

---

## 1. Cycle de vie de la connexion (événements)

C'est la partie qui permet de savoir **quand un joueur se connecte / se déconnecte**.

| Événement | Moment | Données exposées |
|-----------|--------|------------------|
| `PlayerSetupConnectEvent` | Avant l'acceptation de la connexion (annulable) | `getUuid()`, `getUsername()`, `getAuth()`, `getPacketHandler()`, `getReferralData()`, `isReferralConnection()`, `setReason(Message)`, `setCancelled(true)` |
| `PlayerSetupDisconnectEvent` | Échec de connexion / déconnexion précoce | `getUsername()`, `getUuid()`, `getAuth()`, `getDisconnectReason()` |
| `PlayerConnectEvent` | Connexion acceptée, joueur créé | `getPlayerRef()`, `getPlayer()`, `getWorld()`, `getHolder()`, `setWorld(World)` |
| `PlayerReadyEvent` | Joueur prêt (monde chargé, côté client prêt) | `getPlayerRef()` (hérité), `getPlayer()`, `getReadyId()` |
| `AddedPlayerToWorldEvent` | Joueur ajouté à un monde | `getHolder()`, `getWorld()`, `getJoinMessage()`, `setBroadcastJoinMessage(boolean)` |
| `DrainPlayerFromWorldEvent` | Joueur retiré d'un monde (changement de monde) | `getHolder()`, `getWorld()`, `setWorld(...)`, `getTransform()`, `setTransform(...)` |
| `RemovedPlayerFromWorldEvent` | Joueur effectivement retiré du monde | `getHolder()`, `getWorld()`, `getLeaveMessage()`, `setBroadcastLeaveMessage(boolean)` |
| `PlayerDisconnectEvent` | Déconnexion complète | `getPlayerRef()` (hérité de `PlayerRefEvent`), `getDisconnectReason()` |

### Raison de déconnexion

`PlayerDisconnectEvent#getDisconnectReason()` renvoie un `PacketHandler.DisconnectReason` :

- `getServerDisconnectReason()` / `getServerDisconnectReasonFormatted()`
- `getClientDisconnectType()` → `DisconnectType.Disconnect` ou `DisconnectType.Crash`

### Détecter une connexion en cours de setup

`PlayerSetupConnectEvent` est **annulable** : on peut refuser une connexion et définir le
message affiché (`setReason(Message)`). Utile pour un système de maintenance/ban custom.
Il expose aussi `getReferralData()` et `referralToServer(...)` pour le transfert de serveur.

> **Recommandation** : pour l'historique de jeu, enregistrer les timestamps dans
> `PlayerConnectEvent` (arrivée) et `PlayerDisconnectEvent` (départ), et calculer les
> sessions/playtime à partir de ces paires.

---

## 2. Événements de gameplay (par joueur)

| Événement | Contenu |
|-----------|---------|
| `PlayerChatEvent` | `getSender()`, `getTargets()`, `getContent()`, `setContent(...)`, `getFormatter()`, `isCancelled()` |
| `PlayerInteractEvent` | `getActionType()` (`InteractionType`), `getItemInHand()`, `getTargetBlock()`, `getTargetEntity()`, `getClientUseTime()` |
| `PlayerMouseButtonEvent` / `PlayerMouseMotionEvent` | Clic/souris détaillés, item en main, bloc/entité cible, coordonnées écran |
| `PlayerCraftEvent` | Recette craftée (`getCraftedRecipe()`) et quantité |
| `InventoryChangeEvent` / `InventorySetActiveSlotEvent` / `InventoryActiveSlotRequestEvent` | Modifications d'inventaire |
| `BreakBlockEvent` / `PlaceBlockEvent` / `DamageBlockEvent` / `EnvironmentBreakBlockEvent` | Interactions blocs |
| `UseBlockEvent` / `UseEntityEvent` / `InteractivelyPickupItemEvent` / `DropItemEvent` | Utilisation / ramassage / drop |
| `ChangeGameModeEvent` | Changement de mode de jeu |
| `RespawnEvent` | Réapparition |
| `DiscoverZoneEvent` | Découverte de zone |
| `CraftRecipeEvent` | Craft (niveau recette) |

---

## 3. Données du `PlayerRef`

`PlayerRef` est le handle du joueur connecté (composant ECS). API publique :

| Méthode | Description |
|---------|-------------|
| `getUuid()` | UUID du joueur |
| `getUsername()` | Pseudo |
| `getReference()` / `getHolder()` | Référence/holder ECS |
| `isValid()` | Le joueur est-il encore valide |
| `getLanguage()` | Langue du client |
| `getTransform()` | Position + rotation courantes |
| `getWorldUuid()` / `setWorldUuid(UUID)` | Monde courant |
| `getHeadRotation()` | Rotation de la tête |
| `getPacketHandler()` | Connexion réseau (voir section 6) |
| `getChunkTracker()` | Suivi de chunks |
| `getHiddenPlayersManager()` | Gestion des joueurs masqués |
| `getTeleportAckTracker()` | Acquittement de téléportation |
| `hasPermission(String)` / `hasPermission(PermissionQuery)` | Permissions |
| `sendMessage(Message)` | Envoyer un message |

**Accès global** : `Universe.get().getPlayer(UUID)`, `Universe.get().getPlayerByUsername(String, NameMatching)`,
`Universe.get().getPlayers()`, `Universe.get().getPlayerCount()`.
Par monde : `World.getPlayerRefs()`, `World.getPlayerCount()`.

---

## 4. Données de l'entité `Player`

Via `PlayerConnectEvent#getPlayer()`, `PlayerReadyEvent#getPlayer()` ou le composant
`Player.getComponentType()`.

| Méthode | Description |
|---------|-------------|
| `getPlayerRef()` | `PlayerRef` associé |
| `getGameMode()` | Mode de jeu (`Adventure`, `Creative`, …) |
| `getPlayerConfigData()` | Données persistantes (voir section 5) |
| `isFirstSpawn()` / `setFirstSpawn(bool)` | Premier spawn |
| `isFlying(...)` / `setFlying(...)` | Vol |
| `isNoClip()` / `setNoClip(...)` | No-clip |
| `getViewRadius()` / `getClientViewRadius()` | Distances de vue |
| `getMountEntityId()` | Entité montée |
| `hasSpawnProtection()` | Protection au spawn |
| `getSinceLastSpawnNanos()` | Temps depuis le dernier spawn |
| `getPlayerConnection()` | `PacketHandler` |
| `getWindowManager()` / `getPageManager()` / `getHudManager()` / `getHotbarManager()` | UI joueur |
| `giveItem(ItemStack, ...)` | Donner un item |
| `setGameMode(Ref, GameMode, ...)` (statique) | Changer le mode de jeu |

### Composants ECS utiles

| Composant | Accès |
|-----------|-------|
| `TransformComponent` | `getPosition()`, `getRotation()`, `getTransform()` |
| `PlayerLives` | `getRemaining()`, `markExhausted(...)` |
| `EntityStatMap` | Stats : `getHealth()`, `getStamina()`, `getMana()`, `getOxygen()`, `getAmmo()`, `getSignatureEnergy()` via `DefaultEntityStatTypes` |
| `EntityStatValue` | `get()`, `getMin()`, `getMax()`, `asPercentage()` |
| Composants d'inventaire (`InventoryComponent`, `ActiveSlotInventoryComponent`) | `getActiveSlot()`, `getActiveItem()`, conteneurs hotbar/storage/armor/utility/backpack |
| `Invulnerable`, `HiddenFromAdventurePlayers`, `PreventInventoryAccess`, `PreventEmotes` | États de protection/restriction |

Récupération des stats :
```java
EntityStatMap stats = EntityStatsModule.get(playerEntity); // ou store.getComponent(ref, EntityStatMap.getComponentType())
float health = stats.get(DefaultEntityStatTypes.getHealth()).get();
```

---

## 5. Données persistées du joueur

`PlayerConfigData` (via `Player#getPlayerConfigData()`) :

- `getWorld()`, `getPreset()`, `getBlockIdVersion()`
- `getKnownRecipes()` — recettes connues
- `getDiscoveredZones()`, `getDiscoveredInstances()`
- `getPerWorldData()` → `PlayerWorldData` par monde :
  - `getLastPosition()` (`Transform`)
  - `getRespawnPoints()`, `getDeathPositions()` (`PlayerDeathPositionData`, `PlayerRespawnPointData`)
  - `getUserMapMarkers()`, `isFirstSpawn()`
- `getReputationData()` — réputation par faction/PNJ
- `getActiveObjectiveUUIDs()`

Accès disque : `Universe.get().getPlayerStorage()` (`PlayerStorage`) :

- `load(UUID)`, `save(UUID, holder, bool)`, `update(UUID, Consumer)`, `remove(UUID)`
- `getPlayers()` → ensemble des UUID connus sur le disque

> **Attention** : il n'existe **aucun** champ natif `firstJoin`, `lastSeen` ou `playtime`.
> Ces données doivent être maintenues par le plugin (base de données ou fichier JSON) en
> écoutant les événements de connexion/déconnexion.

---

## 6. Actions possibles sur un joueur

### Messagerie & UI

| Action | API |
|--------|-----|
| Envoyer un message | `PlayerRef#sendMessage(Message)` — `Message.raw(...)`, `Message.translation(...)`, `.color(...)`, `.bold(...)`, `.param(...)` |
| Ouvrir une page/UI | `Player#getPageManager()`, `Player#getWindowManager()`, `Player#getHudManager()` |
| Jouer un son / HUD | via `HudManager` et les `*Page` (`PlaySoundPage`, etc.) |

### Modération

| Action | API |
|--------|-----|
| Kick / déconnexion forcée | `PlayerRef#getPacketHandler().disconnect(Message)` |
| Bannir | `AccessControlModule.get().ban(new Ban(target, by, timestamp, expiresOn, reason))` |
| Débannir | `AccessControlModule.get().unban(UUID)` |
| Vérifier un ban | `AccessControlModule.get().isBanned(UUID)` |
| Whitelist (déjà implémentée) | `allowJoin(UUID)`, `disallowJoin(UUID)`, `setJoinPermissionRequired(bool)` |
| Refuser une connexion en amont | `PlayerSetupConnectEvent#setCancelled(true)` + `setReason(Message)` |

`Ban` expose : `getTarget()`, `getBy()`, `getTimestamp()`, `getExpiresOn()`, `getReason()`,
`isInEffect()`, `getDisconnectReason()`.

### Déplacement & état

| Action | API |
|--------|-----|
| Téléporter | `TransformComponent#teleportPosition(...)` / `setPosition(...)` via le store |
| Position/rotation | `PlayerRef#getTransform()`, `TransformComponent` |
| Changer le mode de jeu | `Player#setGameMode(ref, GameMode, accessor)` |
| Vol / no-clip | `Player#setFlying(...)`, `Player#setNoClip(...)` |
| Donner un item | `Player#giveItem(ItemStack, ref, accessor)` |
| Modifier les stats (faim, mana…) | `EntityStatMap#setStatValue/addStatValue/...` |
| Vies (hardcore) | `PlayerLives` |
| Masquer aux autres | `HiddenPlayersManager` |
| Transférer vers un autre monde | `Universe.transferPlayerAsync(...)`, `World`/`Transform` |

### Monde (contexte)

- `World#getWorldConfig()`, `World#getGameModeTypeOnDeath()`
- `World#getDaytimeDurationSeconds()`, `getNighttimeDurationSeconds()`, `getTick()`
- `World#setTicking(bool)`, `World.setTimeDilation(float, accessor)`
- `World#scheduleAfter(Runnable, delay, unit)`

---

## 7. Ce qui n'est PAS disponible nativement

| Besoin | Statut | Contournement |
|--------|--------|---------------|
| Date de première connexion | ❌ absent | Enregistrer au premier `PlayerConnectEvent` |
| Dernière connexion | ❌ absent | Mettre à jour à chaque `PlayerDisconnectEvent` |
| Temps de jeu cumulé | ❌ absent | Sommer les sessions (connect → disconnect) |
| Historique de sanctions | ❌ absent | Base de données du plugin |
| Adresse IP du joueur | ⚠️ via `PacketHandler#getChannel()` / `ChannelConnection` (à confirmer selon transport) | Journaliser à la connexion |
| Historique des morts | ✅ partiel | `PlayerWorldData#getDeathPositions()` (positions, pas timestamps) |
| Recettes/zones découvertes | ✅ | `PlayerConfigData` |
| Réputation | ✅ | `PlayerConfigData#getReputationData()` |

---

## 8. Squelette d'un tracker de sessions

Exemple minimal pour répondre au besoin « savoir quand il s'est connecté/déconnecté » :

```java
public final class PlayerSessionTracker {

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public void onConnect(PlayerConnectEvent event) {
        PlayerRef ref = event.getPlayerRef();
        sessions.put(ref.getUuid(), new Session(ref.getUsername(), Instant.now()));
        // TODO: persister firstJoin / lastSeen dans votre stockage
    }

    public void onDisconnect(PlayerDisconnectEvent event) {
        PlayerRef ref = event.getPlayerRef();
        Session session = sessions.remove(ref.getUuid());
        if (session != null) {
            Duration played = Duration.between(session.startedAt(), Instant.now());
            // TODO: incrémenter le playtime cumulé, logger la session
        }
        var reason = event.getDisconnectReason();
        String type = reason != null && reason.getClientDisconnectType() != null
                ? reason.getClientDisconnectType().name()
                : "UNKNOWN";
        // TODO: logger la raison (Disconnect / Crash)
    }
}
```

Enregistrement dans le plugin (`setup()` / `start()`) :

```java
this.getEventRegistry().registerGlobal(PlayerConnectEvent.class, tracker::onConnect);
this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, tracker::onDisconnect);
```

> `registerGlobal` écoute sur tous les mondes. `register` permet d'écouter un monde
> spécifique ou un key d'événement. `registerAsync`/`registerAsyncGlobal` existent pour
> les événements asynchrones comme `PlayerChatEvent`.

---

## 9. Références de classes

- `com.hypixel.hytale.server.core.universe.PlayerRef`
- `com.hypixel.hytale.server.core.universe.Universe`
- `com.hypixel.hytale.server.core.entity.entities.Player`
- `com.hypixel.hytale.server.core.entity.entities.player.data.PlayerConfigData`
- `com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData`
- `com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap` / `EntityStatValue`
- `com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes`
- `com.hypixel.hytale.server.core.modules.entity.component.TransformComponent` / `PlayerLives`
- `com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule`
- `com.hypixel.hytale.server.core.modules.accesscontrol.ban.Ban`
- `com.hypixel.hytale.server.core.io.PacketHandler` (`DisconnectReason`)
- `com.hypixel.hytale.server.core.event.events.player.*`
- `com.hypixel.hytale.event.EventRegistry`
