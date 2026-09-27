# Webhooks — design

Document de conception. **Aucun code n'est encore écrit** : il sert de référence
pour l'implémentation future.

## Objectif

Le plugin émet des événements joueur vers une **API centrale** (interne), qui tient la
base de données et comptabilise les connexions/déconnexions. C'est ensuite l'API centrale
qui expose, si besoin, vers le site web ou un bot Discord.

## Topologie et frontière de confiance

```text
┌─────────────┐   webhook interne   ┌───────────────┐   sortant   ┌───────────────┐
│ Plugin HSM  │ ──────────────────▶ │ API centrale  │ ──────────▶ │ Site / Discord │
│ (loopback)  │                     │ (BDD)         │             └───────────────┘
└─────────────┘                     └───────────────┘
      ▲
      │ loopback uniquement
   administration
```

- Rien côté plugin ne sort vers Internet ; le webhook reste sur le réseau interne
  (même machine, LAN ou réseau Docker).
- L'API REST d'administration du plugin reste en loopback ; elle n'est pas durcie pour
  Internet et n'a pas à l'être.
- Toute la sécurité externe (auth des bots, CORS, rate limiting) est portée par
  l'API centrale.

## Choix actés

1. **Émettre tous les événements** : `player.connect`, `player.ready`,
   `player.disconnect`.
2. **Outbox persistée dès la v1** : les événements non acquittés sont écrits sur disque
   et rejoués au redémarrage.
3. **Configuration dans un fichier dédié `webhooks.json`** (pas dans
   `management_config.json`), pour supporter plusieurs endpoints à terme.
4. **Authentification par Bearer Token** (secret partagé interne), pas de HMAC.

## Sémantique des événements

| Événement plugin | Type émis | Rôle |
|------------------|-----------|------|
| `PlayerConnectEvent` | `player.connect` | Connexion acceptée, audit et tentatives. |
| `PlayerReadyEvent` | `player.ready` | Début de session réelle (monde chargé, client prêt) : source de vérité du playtime. |
| `PlayerDisconnectEvent` | `player.disconnect` | Fin de session, avec la raison. |

Points de conception :

- `PlayerReadyEvent` peut se déclencher **plusieurs fois** pour un même joueur (resets,
  respawn de session). Il faut **dédupliquer** : ne créer une session que si aucune
  session n'est déjà ouverte pour ce `uuid`.
- `player.disconnect` peut arriver **sans `player.ready`** (connexion échouée, kick
  pendant le chargement). L'API centrale doit tolérer ce cas.
- La raison de déconnexion vient de `PlayerDisconnectEvent#getDisconnectReason()` :
  `DisconnectType.Disconnect` ou `DisconnectType.Crash`.

## Format du payload

Enveloppe stable et versionnée :

```json
{
  "id": "0193f2b0-...",
  "type": "player.ready",
  "version": 1,
  "timestamp": "2026-09-14T22:30:00Z",
  "server": {
    "id": "assos-main",
    "name": "Hytale Server",
    "group": "HytaleAssos"
  },
  "sessionId": "0193f2b0-...",
  "data": {
    "uuid": "00000000-0000-0000-0000-000000000000",
    "username": "Player",
    "world": "default"
  }
}
```

- `id` : identifiant unique de l'événement, sert de clé d'idempotence.
- `sessionId` : généré au `player.ready`, réutilisé au `player.disconnect`.
- `data` pour `player.disconnect` ajoute `disconnectReason` et `crashed` (booléen).
- `data` pour `player.connect` contient `uuid`, `username`, `world`.

## En-têtes HTTP

```text
Content-Type: application/json
User-Agent: HytaleServerManagement/0.1.0
Authorization: Bearer <webhook token>
X-HSM-Event: player.ready
X-HSM-Delivery: <id de livraison>
X-HSM-Timestamp: <epoch millis>
```

## Configuration : `webhooks.json`

Fichier dédié, distinct de `management_config.json`. Il est généré dans le dossier de
données du plugin au premier lancement et **ignoré par git** (il contient un token).

```json
{
  "enabled": true,
  "serverId": "assos-main",
  "queueSize": 4096,
  "outboxEnabled": true,
  "outboxDirectory": "webhook_outbox",
  "requestTimeoutMs": 5000,
  "maxRetries": 5,
  "retryBackoffMs": 1000,
  "endpoints": [
    {
      "id": "central-api",
      "enabled": true,
      "url": "http://127.0.0.1:9000/hooks/hytale",
      "token": "",
      "events": ["player.connect", "player.ready", "player.disconnect"]
    }
  ]
}
```

| Clé | Défaut | Description |
|-----|--------|-------------|
| `enabled` | `true` | Active l'envoi des webhooks. |
| `serverId` | `""` | Identifiant du serveur, écrit dans `server.id`. |
| `queueSize` | `4096` | Taille de la file interne. |
| `outboxEnabled` | `true` | Persistance disque pour rejeu après redémarrage. |
| `outboxDirectory` | `webhook_outbox` | Dossier d'outbox, relatif au data directory. |
| `requestTimeoutMs` | `5000` | Timeout par requête HTTP. |
| `maxRetries` | `5` | Tentatives par événement. |
| `retryBackoffMs` | `1000` | Base du backoff exponentiel. |
| `endpoints[].events` | tous | Types auxquels l'endpoint est abonné. |

## Livraison

Un webhook ne doit **jamais bloquer le thread du serveur de jeu**.

1. **EventHandler** (thread jeu) : construit l'événement, l'écrit dans l'outbox et
   l'enfile. Retour immédiat.
2. **Queue bornée** : si pleine, l'événement reste dans l'outbox (pas de perte) et un
   avertissement est journalisé.
3. **Worker(s)** dédié(s) : `java.net.http.HttpClient` du JDK, pas de dépendance ajoutée.

Sémantique : **at-least-once**. Le consommateur doit être idempotent (clé `id`).

- **Ordre par joueur** : sharding par UUID (même joueur → même worker) pour ne jamais
  traiter un `disconnect` avant son `ready`.
- **Retry** : backoff exponentiel avec jitter, plafonné à `maxRetries`.
- **Circuit breaker** : back off global si l'endpoint est durablement indisponible.
- **Shutdown** : flush de la queue avec un timeout court.

## Outbox

- Chaque événement non acquitté est écrit sur disque (`outboxDirectory`) avant tout envoi.
- Un événement est supprimé de l'outbox après un `2xx`.
- Au démarrage, l'outbox est relue et les événements rejoués dans l'ordre.
- Garantit qu'un crash serveur ou une API centrale indisponible ne fait pas perdre la
  comptabilisation.

## Observabilité

- `GET /api/v1/webhooks/status` : enabled, endpoints, profondeur de queue, compteurs
  sent / failed / dropped / outbox pending.
- `POST /api/v1/webhooks/test` : envoie un payload d'exemple vers un endpoint, pour
  déboguer sans attendre une vraie connexion.
- Compteurs repris dans `GET /api/v1/status`.
- Logs : FINE en succès, WARNING en échec.

## Côté API centrale (à concevoir plus tard)

- Vérifier le Bearer token.
- **Idempotence** : table `received_events(id PRIMARY KEY)` ; ignorer les doublons.
- `player.connect` : journaliser la connexion.
- `player.ready` : UPSERT d'une session ouverte (`sessionId`, uuid, `started_at`).
- `player.disconnect` : clôturer la session, calculer `duration`, cumuler le playtime.
- Tolérer le désordre et les disconnect sans ready.
- Répondre `2xx` rapidement, traiter en asynchrone.

## Hors périmètre v1 (phase 2)

- Batching de plusieurs événements par requête.
- Filtres par monde ou par joueur.
- Politique de retry fine par endpoint.
- Dead-letter avancée (fichier dédié, réinjection manuelle).
- Autres types d'événements (`player.chat`, `player.death`, bans).

## Sécurité et données

- Token stocké dans `webhooks.json`, jamais commité.
- `webhooks.json` et le dossier d'outbox doivent être ajoutés au `.gitignore`.
- Données collectées : UUID, pseudo, monde, horodatage, raison de déconnexion. Pas
  d'adresse IP. Prévoir une politique de conservation côté API centrale.
