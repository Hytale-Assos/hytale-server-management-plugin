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

> Si `ApiRequireAuth` est vrai et que `ApiToken` est vide, un token aléatoire est généré
> et écrit dans le fichier. Consultez le fichier pour le récupérer.

## API REST

Base : `/api/v1` — toutes les réponses sont en JSON.

| Méthode  | Endpoint                          | Description                              |
|----------|-----------------------------------|------------------------------------------|
| `GET`    | `/health`                         | Ping simple + état whitelist.            |
| `GET`    | `/status`                         | État complet du plugin, serveur, API.    |
| `POST`   | `/reload`                         | Recharge la config et redémarre l'API.   |
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

## Architecture

```text
assos.hytale.servermanagement
├── ServerManagementPlugin     # entrée JavaPlugin + implémentation ManagementFacade
├── config/ManagementConfig    # config typée via BuilderCodec
├── whitelist/
│   ├── WhitelistService       # wrapper thread-safe de AccessControlModule
│   ├── ProfileResolver        # pseudo/UUID -> profil Hytale
│   └── WhitelistEntry         # DTO
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
