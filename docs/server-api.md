# Rapport — API serveur Hytale (0.6.5)

Ce document recense ce qu'un plugin serveur peut **observer** et **piloter** sur le
serveur Hytale lui-même via `com.hypixel.hytale.server.core`. Il complète
[`player-api.md`](player-api.md), qui couvre le joueur, en se concentrant sur la partie
technique : version et mises à jour, état du serveur, mondes, sauvegardes, reprise après
crash, configuration et commandes natives.

> Toutes les classes citées proviennent du serveur Hytale. Les informations ont été
> extraites du jar `HytaleServer.jar` installé (version `0.6.5`, patchline `release`).
> Aucune API « console d'administration » clé-en-main n'existe : les actions serveur
> passent soit par des singletons (`HytaleServer`, `Universe`, `UpdateModule`), soit par
> l'exécution de commandes natives via `CommandManager`.

---

## 1. Version du serveur et mises à jour

### 1.1 Version courante

La version, la révision et la patchline sont lues dans le manifeste du jar et exposées
par `com.hypixel.hytale.common.util.java.ManifestUtil` :

| Méthode | Description |
|---------|-------------|
| `ManifestUtil.getImplementationVersion()` | Version (`0.6.5`) |
| `ManifestUtil.getVersion()` | Version |
| `ManifestUtil.getImplementationRevisionId()` | Hash de révision (commit) |
| `ManifestUtil.getPatchline()` | Patchline courante (`release`, `beta`, …) |
| `ManifestUtil.isJar()` | Le serveur tourne-t-il depuis un jar (update possible) |

### 1.2 Vérifier une mise à jour

`com.hypixel.hytale.server.core.update.UpdateService` porte toute la logique :

| Méthode | Description |
|---------|-------------|
| `checkForUpdate(String patchline)` | `CompletableFuture<VersionManifest>` : dernière version publiée sur la patchline |
| `fetchPatchlines(String token)` | Liste des patchlines disponibles (`PatchlineSummary[]`) |
| `getEffectivePatchline()` (statique) | Patchline effective (config serveur, sinon manifeste) |
| `downloadUpdate(manifest, dir, callback)` | Télécharge vers un dossier de staging (`DownloadTask`) |
| `performBootstrapInstall(manifest, callback)` | Installe en mode bootstrap (`DownloadTask`) |
| `readVersionFromJar(Path)` (statique) | Lit la version d'un jar Hytale |
| `isValidUpdateLayout()` (statique) | L'installation supporte-t-elle la mise à jour |
| `getStagedVersion()` (statique) | Version actuellement préparée, ou `null` |
| `getStagingDir()` / `getBackupDir()` (statique) | Dossiers de staging et de sauvegarde d'update |
| `deleteStagedUpdate()` / `deleteBackupDir()` (statique) | Nettoyage |

DTO associés :

- `UpdateService.VersionManifest` : `version`, `downloadUrl`, `sha256`.
- `UpdateService.PatchlineSummary` : `name`, `expiresAt` (epoch).
- `UpdateService.DownloadTask` (record) : `future()` (`CompletableFuture<Boolean>`), `thread()`.
- `UpdateService.ProgressCallback` : `onProgress(int percent, long downloaded, long total)`.

> **Prérequis d'authentification** : `checkForUpdate` récupère une URL signée auprès du
> service de compte Hytale. La commande native vérifie d'abord
> `ServerAuthManager.getInstance().hasSessionToken()`. Sans session en ligne
> (`/auth`), la vérification échoue. Le plugin doit donc gérer proprement ce cas.

### 1.3 Suivre l'état d'une mise à jour

`com.hypixel.hytale.server.core.update.UpdateModule` est un module noyau (`JavaPlugin`)
accessible via `UpdateModule.get()` — c'est l'état vivant du système d'update :

| Méthode | Description |
|---------|-------------|
| `getLatestKnownVersion()` / `setLatestKnownVersion(manifest)` | Dernière version connue (renseignée par `/update check`) |
| `isDownloadInProgress()` | Un téléchargement est en cours |
| `getDownloadProgress()` | `DownloadProgress(int percent, long downloadedBytes, long totalBytes, long etaSeconds)` |
| `tryAcquireDownloadLock()` / `releaseDownloadLock()` | Verrou de téléchargement |
| `cancelDownload()` | Annule le téléchargement en cours |
| `onServerReady()` | Hook interne appelé quand le serveur est prêt |

`UpdateModule.KILL_SWITCH_ENABLED` indique si le vérificateur d'update a été désactivé
globalement.

### 1.4 Configuration des mises à jour

`com.hypixel.hytale.server.core.config.UpdateConfig` (section `Update` de `config.json`) :

| Getter | Défaut | Description |
|--------|--------|-------------|
| `isEnabled()` | `true` | Vérificateur d'update actif |
| `getCheckIntervalSeconds()` | `3600` | Intervalle de vérification |
| `isNotifyPlayersOnAvailable()` | `true` | Prévenir les joueurs connectés |
| `getPatchline()` | `""` | Patchline ciblée (vide = manifeste) |
| `isRunBackupBeforeUpdate()` | `true` | Sauvegarde complète avant application |
| `isBackupConfigBeforeUpdate()` | `true` | Sauvegarde des fichiers de config avant application |
| `getAutoApplyMode()` | `DISABLED` | `DISABLED`, `WHEN_EMPTY`, `SCHEDULED` |
| `getAutoApplyDelayMinutes()` | `30` | Délai pour le mode `SCHEDULED` |

Le vérificateur est automatiquement désactivé si le serveur ne tourne pas depuis un jar
(`ManifestUtil.isJar() == false`) ou en mode singleplayer (`Constants.SINGLEPLAYER`).

### 1.5 Commandes natives d'update

| Commande | Rôle |
|----------|------|
| `/update check` | Vérifie une mise à jour et renseigne `UpdateModule#setLatestKnownVersion` |
| `/update download [--force]` | Télécharge la mise à jour vers le staging |
| `/update apply [--confirm]` | Applique la mise à jour (sauvegarde config incluse) |
| `/update status` | Affiche la version connue et l'état du téléchargement |
| `/update cancel` | Annule le téléchargement |
| `/update patchline <nom>` | Change la patchline |
| `/update setup [--force]` | Prépare une installation bootstrap |

> Les fichiers de configuration sauvegardés par `apply` sont listés dans
> `UpdateApplyCommand.CONFIG_FILES` (champ privé). L'application nécessite un script de
> lancement ; sinon le message « No launcher script detected - update must be applied
> manually after shutdown » est émis et l'arrêt du serveur est déclenché avec
> `ShutdownReason.UPDATE`.

---

## 2. État et cycle de vie du serveur

### 2.1 `HytaleServer` (singleton)

`com.hypixel.hytale.server.core.HytaleServer`, accessible par `HytaleServer.get()` :

| Membre | Description |
|--------|-------------|
| `getConfig()` | `HytaleServerConfig` en mémoire |
| `isBooting()` / `isBooted()` / `isShuttingDown()` | État du cycle de vie |
| `getBoot()` | `Instant` de démarrage |
| `getBootStart()` | Timestamp de début de boot |
| `getShutdownReason()` | Raison de l'arrêt en cours |
| `getServerName()` | Nom du serveur |
| `shutdownServer()` / `shutdownServer(ShutdownReason)` | Arrêt du serveur |
| `getEventBus()` | `EventBus` global |
| `getPluginManager()` | Gestion des plugins |
| `getCommandManager()` | Gestion des commandes |
| `SCHEDULED_EXECUTOR` | `ScheduledExecutorService` partagé |
| `METRICS_REGISTRY` | Métriques (voir 2.4) |

### 2.2 Raisons d'arrêt — `ShutdownReason`

| Constante | Code de sortie | Nom |
|-----------|----------------|-----|
| `SIGINT` | 130 | `sigint` |
| `SHUTDOWN` | 0 | `normal` |
| `CRASH` | 1 | `crash` |
| `AUTH_FAILED` | 2 | `auth_failed` |
| `WORLD_GEN` | 3 | `world_gen_error` |
| `CLIENT_GONE` | 4 | `client_gone` |
| `MISSING_REQUIRED_PLUGIN` | 5 | `missing_plugin` |
| `VALIDATE_ERROR` | 6 | `validation_error` |
| `MISSING_ASSETS` | 7 | `missing_assets` |
| `UPDATE` | 8 | `update` |
| `MOD_ERROR` | 9 | `mod_error` |
| `VERIFY_ERROR` | 10 | `verify_error` |

Accès : `getExitCode()`, `getName()`, `getMessage()`, `getFormattedMessage()`,
`withMessage(Message)`, `toTelemetryString()`.

### 2.3 Événements de cycle de vie

| Événement | Moment |
|-----------|--------|
| `com.hypixel.hytale.server.core.event.events.BootEvent` | Démarrage du serveur |
| `com.hypixel.hytale.server.core.event.events.PrepareUniverseEvent` | Avant la création de l'univers (`getWorldConfigProvider()` / `setWorldConfigProvider(...)`) |
| `com.hypixel.hytale.server.core.event.events.ShutdownEvent` | Arrêt ; expose des priorités de phase : `TELEMETRY`, `DISCONNECT_PLAYERS`, `UNBIND_LISTENERS`, `SHUTDOWN_WORLDS`, `FLUSH_UNIVERSE_RESOURCES` |
| `...universe.world.events.AllWorldsLoadedEvent` | Tous les mondes sont chargés |
| `...universe.world.events.StartWorldEvent` | Un monde démarre |

### 2.4 Métriques

`com.hypixel.hytale.metrics.MetricsRegistry` (implémente `Codec<T>`) permet de sérialiser
un composant en métriques JSON/Bson via `toMetricResults(T)` / `dumpToJson(...)`.

- `HytaleServer.METRICS_REGISTRY` expose : `Scheduler`, `Time`, `Boot`, `BootStart`,
  `Booting`, `ShutdownReason`, `PluginManager`, `Config`, `JVM`.
- `Universe.METRICS_REGISTRY` expose : `Worlds`, `PlayerCount`.
- `World.METRICS_REGISTRY` (`ExecutorMetricsRegistry`) expose : `Name`, `Alive`,
  `TickLength`, `EntityStore`, `ChunkStore`.

`MetricResults` ne re-expose pas le Bson publiquement (constructeur et `getBson()`
protégés) ; passer par `MetricsRegistry#toMetricResults(T)` puis l'encoder avec le codec.

---

## 3. Mondes

### 3.1 `Universe` (singleton)

`com.hypixel.hytale.server.core.universe.Universe`, via `Universe.get()` :

| Méthode | Description |
|---------|-------------|
| `getWorlds()` | `Map<String, World>` de tous les mondes chargés |
| `getWorld(String)` / `getWorld(UUID)` | Monde par nom ou UUID |
| `getDefaultWorld()` | Monde par défaut |
| `getWorldsPath()` / `getPath()` / `getWorldsDeletedPath()` | Chemins disque |
| `validateWorldPath(String)` | Chemin valide pour un monde |
| `isWorldLoadable(String)` | Le monde peut-il être chargé |
| `addWorld(String[, ...])` | Charge/ajoute un monde (`CompletableFuture<World>`) |
| `loadWorld(String)` / `makeWorld(...)` | Chargement ou création |
| `removeWorld(String)` / `removeWorldExceptionally(...)` | Suppression |
| `getUniverseReady()` | `CompletableFuture<Void>` prêt |
| `getPlayerStorage()` / `getStorageManager()` | Stockage joueurs / gestionnaire I/O |
| `getWorldConfigProvider()` | Provider de `WorldConfig` |
| `runBackup()` | Lance une sauvegarde (voir section 4) |
| `lockSaving()` / `unlockSaving()` / `isSavingLocked()` | Verrou de sauvegarde global |
| `disconnectAllPLayers()` / `shutdownAllWorlds()` | Arrêt des joueurs / mondes |
| `sendMessage(Message)` / `broadcastPacket(...)` | Diffusion |

### 3.2 `World`

`com.hypixel.hytale.server.core.universe.world.World` (étend `TickingThread` et
implémente `Executor`). Constante `SAVE_INTERVAL = 10.0f` (secondes).

| Méthode | Description |
|---------|-------------|
| `getName()` | Nom du monde |
| `getWorldConfig()` | `WorldConfig` courant |
| `getPlayers()` / `getPlayerRefs()` / `getPlayerCount()` / `getNonSpectatorPlayerCount()` | Joueurs |
| `getEntityStore()` / `getChunkStore()` | Stores ECS |
| `isTicking()` / `setTicking(boolean)` | Tick actif |
| `getTick()` | Tick courant |
| `getDaytimeDurationSeconds()` / `getNighttimeDurationSeconds()` | Durées jour/nuit |
| `setTimeDilation(float, accessor)` (statique) | Dilatation du temps |
| `scheduleAfter(Runnable, delay, unit)` | Tâche planifiée sur le thread du monde |
| `getSavePath()` | Dossier de sauvegarde du monde |
| `isSavingLocked()` / `lockSaving()` / `unlockSaving()` | Verrou de sauvegarde du monde |
| `stopIndividualWorld([players])` | Arrêt du monde |
| `drainPlayersTo(World, Collection<PlayerRef>)` | Déplace les joueurs |
| `getNotificationHandler()` | Notifications du monde |
| `getWorldMapManager()` / `getWorldPathConfig()` / `getChunkLighting()` | Sous-systèmes |

### 3.3 `WorldConfig`

`com.hypixel.hytale.server.core.universe.world.WorldConfig` (fichier
`universe/worlds/<nom>/config.json`). Champs notables, tous avec getter/setter :

- Identité : `uuid`, `displayName`, `seed`, `gameMode`, `defaultPermissionGroup`.
- Règles : `isPvpEnabled`, `isFallDamageEnabled`, `isTicking`, `isBlockTicking`,
  `isGameTimePaused`, `gameTime` (`Instant`), `forcedWeather`.
- Sauvegarde : `isSavingPlayers`, `canSaveChunks`, `saveNewChunks`, `canUnloadChunks`.
- Cycle de vie : `isDeleteOnUniverseStart`, `isDeleteOnRemove`, `crashRecovery`.
- Génération : `worldGenProvider`, `worldMapProvider`, `spawnProvider`,
  `chunkStorageProvider`, `chunkConfig` (`ChunkConfig` : `pregenerateRegion`,
  `keepLoadedRegion`), `resourceStorageProvider`, `gameplayConfig`.
- `markChanged()` / `consumeHasChanged()` : suivi des modifications.

### 3.4 Sauvegarder un monde à la demande

Séquence utilisée par la commande native `/save` (`WorldSaveCommand`) :

```java
WorldConfigSaveSystem.saveWorldConfigAndResources(world);          // config + ressources
ChunkSavingSystems.saveChunksInWorld(
        world.getChunkStore().getStore(), world);                  // chunks du monde
```

`ChunkSavingSystems.saveChunksInWorld(Store<ChunkStore>, Executor)` et
`WorldConfigSaveSystem.saveWorldConfigAndResources(World)` renvoient tous deux un
`CompletableFuture<Void>`. `World` étant un `Executor`, il peut être passé comme executor.

### 3.5 Événements de monde

| Événement | Contenu |
|-----------|---------|
| `AddWorldEvent` | Annulable (`setCancelled(true)`) |
| `RemoveWorldEvent` | `getRemovalReason()` → `GENERAL` ou `EXCEPTIONAL` ; annulable |
| `StartWorldEvent` | Monde démarré |
| `AllWorldsLoadedEvent` | Tous les mondes chargés |
| `ChunkSaveEvent` (ecs) | `getChunk()` ; hérite de `CancellableEcsEvent` |
| `ChunkUnloadEvent` / `SectionUnloadEvent` / `MoonPhaseChangeEvent` (ecs) | Événements de chunk |

---

## 4. Sauvegardes (backups)

### 4.1 Configuration

`com.hypixel.hytale.server.core.config.BackupConfig` (section `Backup` de `config.json`) :

| Getter | Défaut | Description |
|--------|--------|-------------|
| `isEnabled()` | `false` | Active les sauvegardes périodiques (`--backup` force `true`) |
| `getFrequencyMinutes()` | `30` | Fréquence (minimum 1) |
| `getDirectory()` | `null` | Dossier de destination |
| `getMaxCount()` | `5` | Nombre de sauvegardes conservées |
| `getArchiveMaxCount()` | `5` | Nombre d'archives conservées |
| `isConfigured()` | — | `isEnabled() && getDirectory() != null` |

Les options CLI associées (`com.hypixel.hytale.server.core.Options`) :
`--backup`, `--backup-frequency-minutes`, `--backup-directory`,
`--backup-max-count`, `--backup-archive-max-count`. Les valeurs CLI ont priorité sur le
fichier de configuration.

### 4.2 Déclencher une sauvegarde

| API | Description |
|-----|-------------|
| `Universe.get().runBackup()` | Sauvegarde complète (verrou + flush des ressources). Échoue si `getDirectory()` est `null` (`IllegalStateException` « Backup directory not configured ») |
| `BackupTask.start(Path source, Path directory)` | Sauvegarde d'un dossier vers une destination (`CompletableFuture<Void>`) |
| `Universe#lockSaving()` / `unlockSaving()` | Encadrent les opérations sensibles |

La commande native `/backup` vérifie `server.isBooted()`, exige
`getConfig().getBackupConfig().getDirectory() != null`, puis appelle `Universe.runBackup()`.

### 4.3 Format et rétention

`BackupTask` :

- Nom de fichier horodaté au format `yyyy-MM-dd_HH-mm-ss`.
- Rotation : les anciennes sauvegardes sont archivées (`Archived old backup`), puis
  supprimées au-delà de `maxCount` ; les archives sont purgées au-delà de
  `archiveMaxCount`.
- `BackupUtil` (package-privé) fournit les helpers internes : `walkFileTreeAndZip(...)`,
  `findOldBackups(...)`, `broadcastBackupStatus(...)`, `broadcastBackupError(...)`.

> **Attention** : `BackupUtil` n'est pas publique. Un plugin doit utiliser
> `Universe.runBackup()` ou `BackupTask.start(...)`, pas les helpers `BackupUtil`.

### 4.4 Restauration

- `com.hypixel.hytale.server.core.universe.world.storage.provider.BackupChunkLoader`
  (`IChunkLoader` construit à partir d'une liste de chemins de backup) permet de rejouer
  des chunks depuis une sauvegarde.
- `com.hypixel.hytale.server.core.Options.RecoveryMode` :
  `FROM_BACKUP_OR_REGENERATE` ou `REGENERATE` (option `--recovery-mode`), pour la
  récupération au démarrage.
- `UpdateConfig.isRunBackupBeforeUpdate()` / `isBackupConfigBeforeUpdate()` déclenchent
  une sauvegarde avant une mise à jour.

---

## 5. Reprise après crash

`com.hypixel.hytale.server.core.config.CrashRecoveryConfig` (section
`Defaults.CrashRecovery` de `config.json`) :

| Getter | Défaut | Description |
|--------|--------|-------------|
| `getMode()` | `None` | Politique d'un monde qui crash |
| `getMaxAttempts()` | `3` | Tentatives max (limite 100) |
| `getRetryDelaySeconds()` | `5` | Délai entre tentatives (limite 3600) |
| `getFallback()` | `Shutdown` | Politique après échec des tentatives |

`com.hypixel.hytale.server.core.universe.world.WorldCrashRecovery` :
`None`, `Reload`, `Shutdown`.

Le traitement est assuré par `WorldCrashRecoveryHandler` (package-privé, un par
`Universe`) : il écoute `RemoveWorldEvent`, applique la politique résolue
(`ResolvedPolicy` = `mode`, `maxAttempts`, `retryDelaySeconds`, `fallback`) et recharge
le monde (`Reload`) ou applique le fallback. La config peut aussi être définie par monde
dans `WorldConfig#getCrashRecovery()`.

---

## 6. Configuration du serveur

`com.hypixel.hytale.server.core.HytaleServerConfig` (`HytaleServerConfig.PATH` →
`config.json`, `VERSION = 4`) :

| Accès | Description |
|-------|-------------|
| `HytaleServerConfig.load()` / `load(Path)` | Charge la config |
| `HytaleServerConfig.save(config)` / `save(Path, config)` | `CompletableFuture<Void>` d'écriture |
| `getServerName()` / `setServerName` | Nom affiché |
| `getMotd()` / `setMotd` | Message du jour |
| `getPassword()` / `setPassword` | Mot de passe serveur |
| `getMaxPlayers()` / `setMaxPlayers` | Joueurs max |
| `getMaxViewRadius()` / `setMaxViewRadius` | Rayon de vue max |
| `isRequireJoinPermission()` / `setRequireJoinPermission` | Exigence de whitelist |
| `getDefaults()` | `Defaults` : `world`, `gameMode`, `gameModeTypeOnDeath`, `hardcoreMode`, `hardcoreLives`, `crashRecovery` |
| `getConnectionTimeouts()` | `TimeoutProfile` (durées initial/auth/play/setup…) |
| `getRateLimitConfig()` | `RateLimitConfig` (`enabled`, `packetsPerSecond`, `burstCapacity`) |
| `getModules()` / `getModule(String)` | Modules activables |
| `getLogLevels()` | Niveaux de log par logger |
| `getModConfig()` / `getModLoadOrder()` / `getDefaultModsEnabled()` | Mods |
| `getPlayerStorageProvider()` / `getUniverseResourceStorageProvider()` | Stockage |
| `getBanStorageProvider()` / `getAuthCredentialStoreProvider()` | Bannissements / auth |
| `getUpdateConfig()` / `getBackupConfig()` / `getWorldMapConfig()` | Sous-configs |
| `getFallbackServer()` | `HostAddress` de repli |
| `markChanged()` / `consumeHasChanged()` | Suivi des modifications |

Exemple de fichier généré : voir `run/config.json` (section `Update` et `Backup` vides
par défaut dans le dépôt).

### Fichiers et dossiers notables

| Chemin | Rôle |
|--------|------|
| `config.json` | `HytaleServerConfig` |
| `bans.json` | Stockage des bannissements (`BanStorage`) |
| `permissions.json` | Groupes et permissions |
| `auth.enc` | Identifiants chiffrés (`AuthCredentialStore`) |
| `universe/worlds/<nom>/` | Mondes, chunks et `config.json` par monde |
| `universe/players/` | `PlayerStorage` (`<uuid>.json`) |
| `universe/resources/` | Ressources de l'univers |
| `mods/` | Plugins/mods chargés |
| `logs/`, `telemetry/` | Journaux et télémétrie |

---

## 7. Joueurs au niveau serveur

Ces API sont détaillées dans [`player-api.md`](player-api.md) ; rappel des points
« serveur » :

| Besoin | API |
|--------|-----|
| Lister / compter | `Universe.getPlayers()`, `Universe.getPlayerCount()` |
| Chercher | `Universe.getPlayer(UUID)`, `getPlayerByUsername(String, NameMatching)` |
| Déconnecter tout le monde | `Universe.disconnectAllPLayers()` |
| Bannir / débannir | `AccessControlModule.get().ban(Ban)` / `unban(UUID)` / `isBanned(UUID)` |
| Raison de déconnexion d'un banni | `AccessControlModule#getDisconnectReason(UUID)` |
| Whitelist | `allowJoin(UUID)`, `disallowJoin(UUID)`, `disallowAllJoins()`, `isAllowedToJoin(UUID)`, `getUsersWithJoinGrant()`, `setJoinPermissionRequired(boolean)` |
| Profil / UUID | `ServerAuthManager.getInstance().getProfileServiceClient()` → `ProfileServiceClient` |

---

## 8. Exécuter une commande native depuis un plugin

Le plugin n'enregistre aucune commande, mais peut **déclencher** les commandes natives
(update, backup, save) via `CommandManager` :

```java
CommandManager manager = HytaleServer.get().getCommandManager();
manager.handleCommand(ConsoleSender.INSTANCE, "update check");
```

| Méthode | Description |
|---------|-------------|
| `getCommandManager()` | Sur `HytaleServer` |
| `handleCommand(CommandSender, String)` | Exécute une ligne (`CompletableFuture<Void>`) |
| `handleCommands(CommandSender, Deque<String>)` | Exécute une file de commandes |
| `resolveCommand(String)` | Résout une commande |
| `suggestCompletions(...)` | Complétion |
| `ConsoleSender.INSTANCE` | Émetteur console (ne pas confondre avec un joueur) |

> Les commandes s'exécutent de façon asynchrone. Une commande peut ne rien faire si un
> prérequis n'est pas rempli (ex. `/update check` sans token de session). Le plugin doit
> vérifier l'état via les API (`ServerAuthManager#hasSessionToken`,
> `UpdateModule#isDownloadInProgress`, `BackupConfig#isConfigured`) plutôt que de
> supposer que la commande a réussi.

---

## 9. Ce qui n'est PAS disponible nativement

| Besoin | Statut | Contournement |
|--------|--------|---------------|
| API Java « vérifier une update » sans auth | ❌ | Exige un session token (`ServerAuthManager`) ou passer par `/update check` |
| Événement « mise à jour disponible » | ❌ | Poller `UpdateModule#getLatestKnownVersion()` / `getDownloadProgress()` |
| Événement « backup terminé » | ⚠️ | Pas d'événement public ; observer la fin du `CompletableFuture` de `runBackup()` |
| Programmation d'un arrêt différé | ❌ | `HytaleServer.SCHEDULED_EXECUTOR` + `shutdownServer(reason)` |
| Redémarrage automatique du process | ❌ | Géré par le script de lancement / le launcher, pas par le serveur |
| Historique des sauvegardes exposé | ⚠️ | Lister soi-même le dossier de backup (nom horodaté) |
| Métriques temps réel par API | ⚠️ | `MetricsRegistry#toMetricResults` + encodage, ou dump JSON |
| Statut des mods/plugins détaillé | ⚠️ | `PluginManager#getPlugins()` / `getAvailablePlugins()` / `getState()` |

---

## 10. Squelettes d'usage

### 10.1 Statut serveur enrichi

```java
HytaleServer server = HytaleServer.get();
Universe universe = Universe.get();

String version = ManifestUtil.getImplementationVersion();
String patchline = ManifestUtil.getPatchline();
boolean booted = server.isBooted();
int players = universe.getPlayerCount();
int worlds = universe.getWorlds().size();

HytaleServerConfig config = server.getConfig();
boolean whitelistRequired = config.isRequireJoinPermission();
BackupConfig backup = config.getBackupConfig();

UpdateModule updates = UpdateModule.get();
UpdateService.VersionManifest latest = updates != null ? updates.getLatestKnownVersion() : null;
boolean downloading = updates != null && updates.isDownloadInProgress();
```

### 10.2 Vérifier une mise à jour (asynchrone)

```java
if (!ServerAuthManager.getInstance().hasSessionToken()) {
    // pas de session en ligne : impossible de vérifier
} else {
    UpdateService service = new UpdateService();
    service.checkForUpdate(UpdateService.getEffectivePatchline())
           .thenAccept(manifest -> {
               String latest = manifest.version;
               String current = ManifestUtil.getImplementationVersion();
               // comparer et journaliser
           });
}
```

### 10.3 Déclencher une sauvegarde

```java
BackupConfig backup = HytaleServer.get().getConfig().getBackupConfig();
if (!backup.isConfigured()) {
    // définir backup.enabled + directory, puis HytaleServerConfig.save(...)
} else {
    Universe.get().runBackup().whenComplete((ignored, error) -> {
        // succès ou erreur (aucun événement public dédié)
    });
}
```

### 10.4 Sauvegarder un monde et arrêter proprement

```java
World world = Universe.get().getWorld("default");
WorldConfigSaveSystem.saveWorldConfigAndResources(world)
        .thenCompose(ignored -> ChunkSavingSystems.saveChunksInWorld(
                world.getChunkStore().getStore(), world))
        .thenRun(() -> HytaleServer.get().shutdownServer(ShutdownReason.SHUTDOWN));
```

---

## 11. Références de classes

- `com.hypixel.hytale.common.util.java.ManifestUtil`
- `com.hypixel.hytale.server.core.HytaleServer` / `ShutdownReason`
- `com.hypixel.hytale.server.core.HytaleServerConfig` (`Defaults`, `TimeoutProfile`, `Module`)
- `com.hypixel.hytale.server.core.Options` (`RecoveryMode`)
- `com.hypixel.hytale.server.core.config.UpdateConfig` (`AutoApplyMode`) / `BackupConfig` / `CrashRecoveryConfig` / `RateLimitConfig` / `ServerWorldMapConfig` / `ModConfig`
- `com.hypixel.hytale.server.core.update.UpdateService` (`VersionManifest`, `PatchlineSummary`, `DownloadTask`, `ProgressCallback`)
- `com.hypixel.hytale.server.core.update.UpdateModule` (`DownloadProgress`)
- `com.hypixel.hytale.server.core.util.backup.BackupTask` / `BackupUtil` (package-privé)
- `com.hypixel.hytale.server.core.universe.Universe`
- `com.hypixel.hytale.server.core.universe.world.World` / `WorldConfig` (`ChunkConfig`) / `WorldConfigProvider` / `WorldCrashRecovery`
- `com.hypixel.hytale.server.core.universe.WorldCrashRecoveryHandler` (package-privé)
- `com.hypixel.hytale.server.core.universe.world.events.*`
- `com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems`
- `com.hypixel.hytale.server.core.universe.system.WorldConfigSaveSystem`
- `com.hypixel.hytale.server.core.universe.world.storage.provider.BackupChunkLoader`
- `com.hypixel.hytale.server.core.command.system.CommandManager`
- `com.hypixel.hytale.server.core.console.ConsoleSender`
- `com.hypixel.hytale.server.core.plugin.PluginManager`
- `com.hypixel.hytale.server.core.event.events.BootEvent` / `PrepareUniverseEvent` / `ShutdownEvent`
- `com.hypixel.hytale.metrics.MetricsRegistry` / `MetricResults` / `MetricProvider`
