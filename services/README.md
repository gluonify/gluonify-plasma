# gluonify-plasma-services

Joindre un service de Gluonify **sans aucune configuration** : ni URL ni jeton dans l'application. `GluonifyServices.endpoint("crystallon")` rend l'adresse du service et la source de ses jetons, selon l'endroit où tourne l'application. Java 17, **aucune dépendance** (`java.net.http`, JSON interne), aucune réflexion : image native sans configuration. C'est la base de `CrystallonClient.create()`, du `FermionTokenProvider` par défaut, des producteurs de `gluonify-plasma-quarkus`, de `GluonifyTestServices` et de la CLI `gluonify`.

```java
Endpoint crystallon = GluonifyServices.endpoint("crystallon");     // GluonifyServicesException si le service ne peut pas être joint : le message dit quoi faire
URI render = crystallon.resolve("/v1/render");                      // url() : adresse de base, sans barre finale
Optional<String> token = crystallon.token();                         // à chaque appel : relit le fichier, renouvelle le jeton du développeur
crystallon.source();                                                 // OVERRIDE | PLATFORM | DEVELOPER

Optional<Endpoint> optional = GluonifyServices.optional("crystallon"); // service facultatif : vide s'il n'est simplement pas disponible
```

## Ordre de résolution

Le même pour chaque service (`<APP>` : le nom en majuscules, `-` devient `_`).

| # | Cas | Adresse | Jeton (`Endpoint.tokens()`, appelé à chaque requête) |
|---|---|---|---|
| 1 | **Surcharge** (`OVERRIDE`) : propriété système `GLUONIFY_SERVICE_<APP>_URL`, ou variable d'environnement du même nom **hors de la plateforme** | la valeur | `GLUONIFY_<APP>_TOKEN` (fixe) ou `GLUONIFY_<APP>_TOKEN_FILE` (relu), propriété système d'abord, sinon aucun |
| 2 | **Plateforme** (`PLATFORM`) : `GLUONIFY_ENVIRONMENT` présent | `GLUONIFY_SERVICE_<APP>_URL`, injectée pour un service de `uses` | le jeton d'application, fichier `GLUONIFY_<APP>_TOKEN_FILE` déposé et renouvelé par l'agent du nœud (absent : pas encore de jeton) |
| 3 | **Poste du développeur** (`DEVELOPER`) : la zone courante de `~/.gluonify/config.json` (ou `GLUONIFY_ZONE`) | l'adresse **publique** du service dans le document de découverte de la zone | le jeton du développeur, `~/.gluonify/tokens/<zone>.json`, renouvelé par le jeton de rafraîchissement quand il expire dans moins d'une minute |
| 4 | Sinon | `GluonifyServicesException` : « this is not the Gluonify platform … and no cluster is known … Run gluonify login <zone> (for example gluonify login gluonify.lan), set GLUONIFY_SERVICE_<APP>_URL, or use fake services in tests (GluonifyTestServices.start()) » | |

Messages des autres échecs (en anglais, sans jamais de jeton) : service non accordé sur la plateforme (« the platform did not grant the service "crystallon" … build it with Bup with the Gluonify SDK module of the service on its runtime classpath, or, for a call outside the SDK, add "crystallon" to "uses" in gluonify.json; then deploy again » : Bup déduit `uses` des marqueurs du SDK, voir le README racine) ; pas connecté à la zone (« run gluonify login <zone> ») ; session expirée ou révoquée ; zone qui ne publie pas le service (avec la liste de ceux qu'elle publie) ; jeton du développeur sans l'audience du service ; découverte injoignable ou désactivée (« is the cluster running? », PRD). `resolveOptional` / `optional` rend vide pour « pas dans `uses` », « aucun cluster connu » et « service non publié », et lève pour le reste.

## Sur le poste du développeur

`gluonify login <zone>` ([CLI](../cli/README.md)) écrit deux fichiers, dans `~/.gluonify` (ou `GLUONIFY_HOME`), dossiers 0700, fichiers 0600, écrits atomiquement :

```json
// ~/.gluonify/config.json : aucune donnée secrète
{ "current": "gluonify.lan",
  "zones": { "gluonify.lan": { "discovery": "https://gluonify.lan/.well-known/gluonify", "environment": "SBX", "caFile": "/…/pebble-root.pem" } } }

// ~/.gluonify/tokens/gluonify.lan.json
{ "zone": "gluonify.lan", "clientId": "gluonify-cli", "tokenEndpoint": "https://gluonify.lan/realms/gluonify/token", "subject": "ada",
  "audiences": ["gluonify-crystallon", "gluonify-fermion"], "accessToken": "…", "expiresAt": 1791000000, "refreshToken": "…", "refreshExpiresAt": 1793000000 }
```

- **Document de découverte** `https://<zone>/.well-known/gluonify` (publié par Higgs, public, sans secret, désactivé en PRD par défaut), gardé **5 minutes** en mémoire ; s'il devient injoignable, la dernière copie lue sert encore. Délais : 5 s de connexion, 10 s de réponse, 1 Mio au plus. Format (`Discovery`) :

  ```json
  { "zone": "gluonify.lan", "environment": "SBX", "issuer": "https://gluonify.lan/realms/gluonify",
    "openidConfiguration": "https://gluonify.lan/realms/gluonify/.well-known/openid-configuration",
    "cli": { "clientId": "gluonify-cli" },
    "services": { "crystallon": { "url": "https://adm.gluonify.lan/crystallon", "audience": "gluonify-crystallon" } } }
  ```

  `openidConfiguration` et `cli` sont facultatifs (défauts : `issuer` + `/.well-known/openid-configuration`, `gluonify-cli`). Une adresse qui n'est ni `https` ni la boucle locale est ignorée (découverte, point de jeton et services compris) : un jeton ne part jamais en clair hors de la machine.
- **Jeton du développeur** : un seul jeton Charm d'une heure porte les audiences de tous les services (celles du client `gluonify-cli`). Charm fait tourner le jeton de rafraîchissement (usage unique) : le renouvellement se fait sous verrou, entre fils (`synchronized`) et entre processus (`FileLock` sur `<zone>.json.lock`), avec relecture du fichier sous le verrou ; le nouveau couple est réécrit atomiquement. Deux applications lancées en même temps sur le poste ne cassent donc pas la session.
- **Autorité locale** (cluster de développement signé par Pebble) : `caFile` (`gluonify login … --ca <pem>`) est acceptée **en plus** des autorités du JDK, pour la découverte et le renouvellement. Les appels de l'application au service (Crystallon…) passent par le client HTTP de son SDK : pour une autorité locale, l'ajouter au truststore de la JVM (`-Djavax.net.ssl.trustStore`) ou passer un `HttpClient` au client.

## API

| Type | Rôle |
|---|---|
| `GluonifyServices` | `endpoint(service)` / `optional(service)` (instance du processus, `defaults()`), `builder()` (environnement, propriétés, dossier, horloge, durée de vie de la découverte, `HttpClient` : tests et outils), `resolve`, `resolveOptional`, `onPlatform()`, `discovery(zone)`, `invalidate()`, `urlKey`, `tokenKey`, `tokenFileKey` |
| `Endpoint` | `service`, `url`, `tokens` (`Supplier<String>`, `null` = pas de jeton), `source`, `audience`, `zone` ; `token()`, `resolve(path)` ; `toString()` sans jeton |
| `GluonifyServicesException` | `IllegalStateException` dont le message dit quoi faire |
| `Discovery`, `DeveloperConfig`, `DeveloperTokens` | le document de découverte, `config.json`, les jetons du développeur (lecture, écriture 0600, renouvellement) : utilisés par la CLI |

Le paquet `io.gluonify.plasma.services.internal` (JSON, HTTP, fichiers) n'est pas une API. Rien n'est journalisé ; aucun `HttpClient` statique (celui de l'instance est créé au premier appel réseau, un par autorité de certification).

## Tests

`mvn clean verify` : 22 tests (`GluonifyServicesTest`, `DiscoveryTest`) contre un faux cluster (`FakeCluster` : découverte et faux Charm, PKCE et rafraîchissement à usage unique ; publié dans le test-jar pour la CLI et fermion-client) avec un dossier temporaire : les quatre cas, la surcharge sur la plateforme, le cache de 5 minutes et la copie gardée, le jeton expiré renouvelé (rotation, droits 0600), un seul renouvellement pour 16 appels concurrents, la session expirée, l'audience absente, `GLUONIFY_ZONE`, la découverte injoignable ou en clair, les messages.
