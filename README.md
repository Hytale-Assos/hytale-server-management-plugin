# Hytale Server Management

Plugin de gestion de serveur Hytale développé par l'association étudiante **HytaleAssos**.

La première fonctionnalité est la **gestion de la whitelist native**, exposée via une
**API REST sécurisée**. Le plugin n'enregistre aucune commande en jeu : toute
l'administration se fait par l'API HTTP.

## Stack

- Java 25
- [Hytale Gradle Plugin](https://github.com/AzureDoom/Hytale-Gradle-Plugin) (`com.azuredoom.hytale-tools`)
- Serveur ciblé : Hytale `>=0.6.5 <0.7.0`
- HTTP : `com.sun.net.httpserver` du JDK (aucune dépendance à embarquer)
- JSON : Gson fourni par le serveur Hytale

## Démarrage

```bash
./gradlew setupHytaleDev   # prépare l'environnement de dev
./gradlew runServer        # lance le serveur local
./gradlew build            # construit le plugin
```

Le jar est généré dans `build/libs/`.

## Configuration

Le fichier `management_config.json` est créé au premier lancement dans le dossier de
données du plugin et contient :

| Clé                       | Défaut      | Description                                                        |
|---------------------------|-------------|--------------------------------------------------------------------|
| `ApiEnabled`              | `true`      | Active l'API REST.                                                 |
| `ApiHost`                 | `127.0.0.1` | Adresse d'écoute.                                                  |
| `ApiPort`                 | `8080`      | Port d'écoute.                                                     |
| `ApiToken`                | *(vide)*    | Token Bearer. Généré automatiquement au premier démarrage si vide. |
| `ApiRequireAuth`          | `true`      | Exige le header `Authorization: Bearer <token>`.                   |
| `AllowRemoteManagement`   | `false`     | Autorise une écoute hors loopback (déconseillé).                   |
| `LogRequests`             | `true`      | Journalise chaque requête.                                         |
| `DisconnectOnWhitelistRemoval` | `true` | Déconnecte en jeu un joueur retiré de la whitelist.               |
| `ApiCoreEnabled`          | `false`     | Active l'envoi des sessions joueur vers `api-core`.                |
| `ApiCoreUrl`              | `http://127.0.0.1:3000` | URL de base de l'API centrale.                        |
| `ApiCoreApiKey`           | *(vide)*    | API key du module `api-core` représentant le plugin.               |
| `ApiCoreHmacSecret`       | *(vide)*    | Secret HMAC du module, pour signer les écritures.                  |
| `ApiCoreServerId`         | *(vide)*    | UUID du serveur Hytale enregistré dans `api-core`.                 |

> Si `ApiRequireAuth` est vrai et que `ApiToken` est vide, un token aléatoire est généré
> et écrit dans le fichier. Consultez le fichier pour le récupérer.

## API REST

Base : `/api/v1` — toutes les réponses sont en JSON.

| Méthode  | Endpoint                          | Description                              |
|----------|-----------------------------------|------------------------------------------|
| `GET`    | `/health`                         | Ping simple + état whitelist.            |
| `GET`    | `/status`                         | État complet du plugin, serveur, API.    |
| `POST`   | `/reload`                         | Recharge la config et redémarre l'API.   |
| `GET`    | `/link`                           | État de la liaison avec `api-core`.       |
| `POST`   | `/link`                           | Lie l'agent du serveur à `api-core`.      |
| `DELETE` | `/link`                           | Délie l'agent du serveur.                 |
| `GET`    | `/auth` et `/auth/status`         | État de l'authentification serveur.       |
| `POST`   | `/auth/device`                    | Démarre le login OAuth par device code.   |
| `POST`   | `/auth/profile`                   | Choisit un profil (`{"profile":"Nom"}` ou `{"index":0}`). |
| `POST`   | `/auth/logout`                    | Déconnecte le serveur de son compte.      |
| `GET`    | `/whitelist`                      | Liste les entrées + statut.              |
| `GET`    | `/whitelist/status`               | Statut (`enabled`, `count`).             |
| `POST`   | `/whitelist`                      | Ajoute un joueur (`{"player":"..."}`).   |
| `DELETE` | `/whitelist/{uuid-ou-pseudo}`     | Retire un joueur.                        |
| `DELETE` | `/whitelist`                      | Vide la whitelist.                       |
| `POST`   | `/whitelist/enable`               | Active l'obligation de whitelist.        |
| `POST`   | `/whitelist/disable`              | Désactive l'obligation de whitelist.     |

Exemples :

```bash
TOKEN="votre-token"

curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/health
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/status
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"player":"PseudoDuJoueur"}' http://127.0.0.1:8080/api/v1/whitelist
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
     http://127.0.0.1:8080/api/v1/whitelist/PseudoDuJoueur
curl -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/whitelist/enable
curl -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/reload
```

L'ajout résout le pseudo via le service de profils Hytale (joueur en ligne, UUID, puis
service distant). Le serveur doit donc tourner en mode en ligne pour résoudre un pseudo
hors ligne.

Lors d'un `DELETE /whitelist/{identifier}`, si le joueur est connecté et n'a plus le
droit de rejoindre (et que `DisconnectOnWhitelistRemoval` est vrai), il est déconnecté
immédiatement avec le message « You have been removed from the server whitelist. ». La
réponse contient `"disconnected": true|false`. Le vidage complet (`DELETE /whitelist`)
déconnecte tous les joueurs qui ont perdu l'accès et renvoie `"disconnected": <nombre>`.

## Intégration `api-core`

Quand `ApiCoreEnabled` est vrai et que les quatre clés `ApiCore*` sont renseignées,
le plugin pousse les événements de session vers le webhook de l'API centrale
(`POST /api/v1/webhook`) :

- `PlayerReadyEvent` → `session_started` (une seule fois par session) ;
- `PlayerDisconnectEvent` → `session_ended`.

Les requêtes sont signées (`X-Api-Key` + `X-Timestamp` + `X-Signature` HMAC-SHA256)
et envoyées sur un executor dédié : le thread du serveur de jeu n'est jamais
bloqué. Si l'intégration est désactivée ou incomplète, le plugin fonctionne
normalement et journalise l'événement en `FINE`.

### Whitelist pilotée par `api-core`

La liaison à `api-core` est **manuelle et explicite** (rien n'est enregistré au
démarrage) :

| Méthode  | Endpoint        | Description                                             |
|----------|-----------------|---------------------------------------------------------|
| `GET`    | `/api/v1/link`  | État de la liaison (core, serverId, `linked`).          |
| `POST`   | `/api/v1/link`  | Enregistre l'agent du serveur auprès d'`api-core`.       |
| `DELETE` | `/api/v1/link`  | Désenregistre l'agent.                                   |

`POST /api/v1/link` appelle `PUT /api/v1/servers/{ApiCoreServerId}/agent` avec
l'URL loopback du plugin et son token Bearer. `api-core` pousse alors les
ajouts/retraits de whitelist vers `POST /api/v1/whitelist`
(body `{"player":"<uuid>"}`) et `DELETE /api/v1/whitelist/{uuid}`. Les deux
opérations sont **idempotentes** côté plugin (ajouter un joueur déjà whitelisté,
ou retirer un joueur absent, réussit sans erreur) pour supporter les retries de
la file de `api-core`.

Exemple :

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/link
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/link
curl -X DELETE -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/link
```

## Authentification serveur (device flow)

Un serveur hébergé sans console interactive (conteneur, service systemd) peut être
authentifié via l'API, sans TTY ni navigateur sur l'hôte :

1. `POST /auth/device` démarre le flow OAuth « device ». La réponse contient le code
   (`device.userCode`) et l'URL (`device.verificationUri`) dès qu'ils sont connus.
   Le champ `started` indique si un flow a été lancé ; l'appel est idempotent tant
   qu'un flow est en cours.
2. L'opérateur ouvre `device.verificationUriComplete` sur n'importe quel appareil
   (téléphone, navigateur) et valide.
3. Si le compte possède plusieurs profils, la réponse de `GET /auth/status` expose
   `pendingProfiles` ; on choisit avec `POST /auth/profile`
   (`{"profile":"Nom"}` ou `{"index":0}`).
4. `GET /auth/status` reflète `authenticated`, `authMode`, `tokenExpiry` et les
   éventuelles erreurs (`lastError`).

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/auth/device
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/auth/status
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"profile":"MonPseudo"}' http://127.0.0.1:8080/api/v1/auth/profile
curl -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/auth/logout
```

Cette API s'appuie sur `ServerAuthManager` du serveur Hytale, qui n'est pas une API
publique documentée : elle est isolée dans `auth/AuthService`. L'authentification
s'appuie sur `auth.enc` du serveur pour persister les tokens entre redémarrages.

## Architecture

```text
assos.hytale.servermanagement
├── ServerManagementPlugin     # entrée JavaPlugin + implémentation ManagementFacade
├── config/ManagementConfig    # config typée via BuilderCodec
├── whitelist/
│   ├── WhitelistService       # wrapper thread-safe de AccessControlModule
│   ├── ProfileResolver        # pseudo/UUID -> profil Hytale
│   └── WhitelistEntry         # DTO
├── apicore/
│   └── ApiCoreClient          # client HTTP JDK signé (events de session)
├── session/
│   └── SessionTracker         # PlayerReady/Disconnect -> events api-core
├── auth/
│   └── AuthService            # device flow OAuth + statut d'authentification
└── api/
    ├── ApiServer              # HttpServer JDK + garde loopback
    ├── ApiHandler             # routage REST
    ├── ManagementFacade       # pont API <-> plugin
    ├── ApiException           # erreurs HTTP typées
    └── Json                   # helpers Gson
```

## Notes techniques

- La whitelist native Hytale est stockée comme la permission `hytale.server.join` et son
  obligation est pilotée par `AccessControlModule#setJoinPermissionRequired`. Les commandes
  natives `/whitelist` restent donc compatibles.
- Les mutations sont sérialisées sur un executor dédié car le provider de permissions n'est
  pas thread-safe, l'API servant depuis ses propres threads HTTP.
- Par défaut l'API n'écoute qu'en loopback ; un bind distant est refusé sauf
  `AllowRemoteManagement=true`.

## Licence

MIT.
