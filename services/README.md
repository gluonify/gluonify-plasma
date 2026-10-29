# gluonify-plasma-services

Reach a Gluonify service **with no configuration at all**: no URL and no token in the application. `GluonifyServices.endpoint("crystallon")` returns the address of the service and the source of its tokens, depending on where the application runs. Java 17, **no dependency** (`java.net.http`, internal JSON), no reflection: native image without configuration. It is the foundation of `CrystallonClient.create()`, of the default `FermionTokenProvider`, of the producers of `gluonify-plasma-quarkus`, of `GluonifyTestServices` and of the `gluonify` CLI.

```java
Endpoint crystallon = GluonifyServices.endpoint("crystallon");     // GluonifyServicesException if the service cannot be reached: the message says what to do
URI render = crystallon.resolve("/v1/render");                      // url(): base address, without a trailing slash
Optional<String> token = crystallon.token();                         // on each call: rereads the file, renews the developer's token
crystallon.source();                                                 // OVERRIDE | PLATFORM | DEVELOPER

Optional<Endpoint> optional = GluonifyServices.optional("crystallon"); // optional service: empty if it is simply not available
```

## Resolution order

The same for every service (`<APP>`: the name in upper case, `-` becomes `_`).

| # | Case | Address | Token (`Endpoint.tokens()`, called on every request) |
|---|---|---|---|
| 1 | **Override** (`OVERRIDE`): system property `GLUONIFY_SERVICE_<APP>_URL`, or environment variable of the same name **outside the platform** | the value | `GLUONIFY_<APP>_TOKEN` (fixed) or `GLUONIFY_<APP>_TOKEN_FILE` (reread), system property first, otherwise none |
| 2 | **Platform** (`PLATFORM`): `GLUONIFY_ENVIRONMENT` present | `GLUONIFY_SERVICE_<APP>_URL`, injected for a service in `uses` | the application token, file `GLUONIFY_<APP>_TOKEN_FILE` dropped and renewed by the node agent (absent: no token yet) |
| 3 | **Developer workstation** (`DEVELOPER`): the current zone of `~/.gluonify/config.json` (or `GLUONIFY_ZONE`) | the **public** address of the service in the zone's discovery document | the developer's token, `~/.gluonify/tokens/<zone>.json`, renewed with the refresh token when it expires in less than a minute |
| 4 | Otherwise | `GluonifyServicesException`: "this is not the Gluonify platform … and no cluster is known … Run gluonify login <zone> (for example gluonify login gluonify.lan), set GLUONIFY_SERVICE_<APP>_URL, or use fake services in tests (GluonifyTestServices.start())" | |

Messages of the other failures (in English, never containing a token): service not granted on the platform ("the platform did not grant the service "crystallon" … build it with Bup with the Gluonify SDK module of the service on its runtime classpath, or, for a call outside the SDK, add "crystallon" to "uses" in gluonify.json; then deploy again": Bup derives `uses` from the SDK markers, see the root README); not logged in to the zone ("run gluonify login <zone>"); expired or revoked session; zone that does not publish the service (with the list of those it publishes); developer token without the service's audience; discovery unreachable or disabled ("is the cluster running?", PRD). `resolveOptional` / `optional` returns empty for "not in `uses`", "no known cluster" and "service not published", and throws for the rest.

## On the developer's workstation

`gluonify login <zone>` ([CLI](../cli/README.md)) writes two files, in `~/.gluonify` (or `GLUONIFY_HOME`), directories 0700, files 0600, written atomically:

```json
// ~/.gluonify/config.json: no secret data
{ "current": "gluonify.lan",
  "zones": { "gluonify.lan": { "discovery": "https://gluonify.lan/.well-known/gluonify", "environment": "SBX", "caFile": "/…/pebble-root.pem" } } }

// ~/.gluonify/tokens/gluonify.lan.json
{ "zone": "gluonify.lan", "clientId": "gluonify-cli", "tokenEndpoint": "https://gluonify.lan/realms/gluonify/token", "subject": "ada",
  "audiences": ["gluonify-crystallon", "gluonify-fermion"], "accessToken": "…", "expiresAt": 1791000000, "refreshToken": "…", "refreshExpiresAt": 1793000000 }
```

- **Discovery document** `https://<zone>/.well-known/gluonify` (published by Higgs, public, no secret, disabled in PRD by default), kept **5 minutes** in memory; if it becomes unreachable, the last copy read is still used. Timeouts: 5 s to connect, 10 s for the response, 1 MiB at most. Format (`Discovery`):

  ```json
  { "zone": "gluonify.lan", "environment": "SBX", "issuer": "https://gluonify.lan/realms/gluonify",
    "openidConfiguration": "https://gluonify.lan/realms/gluonify/.well-known/openid-configuration",
    "cli": { "clientId": "gluonify-cli" },
    "services": { "crystallon": { "url": "https://adm.gluonify.lan/crystallon", "audience": "gluonify-crystallon" } } }
  ```

  `openidConfiguration` and `cli` are optional (defaults: `issuer` + `/.well-known/openid-configuration`, `gluonify-cli`). An address that is neither `https` nor the loopback interface is ignored (discovery, token endpoint and services included): a token never leaves the machine in clear text.
- **Developer token**: a single one-hour Charm token carries the audiences of all the services (those of the `gluonify-cli` client). Charm rotates the refresh token (single use): renewal happens under a lock, between threads (`synchronized`) and between processes (`FileLock` on `<zone>.json.lock`), with the file reread under the lock; the new pair is rewritten atomically. Two applications started at the same time on the workstation therefore do not break the session.
- **Local authority** (development cluster signed by Pebble): `caFile` (`gluonify login … --ca <pem>`) is accepted **in addition to** the JDK's authorities, for discovery and renewal, and carried by the resolved `Endpoint` (`caFile()`, `sslContext()`: the JDK's authorities, then the zone's; never a context that trusts every certificate). `CrystallonClient` built from the endpoint (`CrystallonClient.create()`, `builder().endpoint(...)`) uses that context, so a development cluster signed by Pebble needs no JVM truststore; an explicit `httpClient(...)` still wins.

## API

| Type | Role |
|---|---|
| `GluonifyServices` | `endpoint(service)` / `optional(service)` (process instance, `defaults()`), `builder()` (environment, properties, folder, clock, discovery lifetime, `HttpClient`: tests and tools), `resolve`, `resolveOptional`, `onPlatform()`, `discovery(zone)`, `invalidate()`, `urlKey`, `tokenKey`, `tokenFileKey` |
| `Endpoint` | `service`, `url`, `tokens` (`Supplier<String>`, `null` = no token), `source`, `audience`, `zone`; `token()`, `resolve(path)`; `toString()` without a token |
| `GluonifyServicesException` | `IllegalStateException` whose message says what to do |
| `Discovery`, `DeveloperConfig`, `DeveloperTokens` | the discovery document, `config.json`, the developer's tokens (read, write 0600, renewal): used by the CLI |

The package `io.gluonify.plasma.services.internal` (JSON, HTTP, files) is not an API. Nothing is logged; no static `HttpClient` (the instance's one is created on the first network call, one per certificate authority).

## Tests

`mvn clean verify`: 22 tests (`GluonifyServicesTest`, `DiscoveryTest`) against a fake cluster (`FakeCluster`: discovery and fake Charm, PKCE and single-use refresh; published in the test-jar for the CLI and fermion-client) with a temporary folder: the four cases, the override on the platform, the 5-minute cache and the kept copy, the expired token renewed (rotation, 0600 permissions), a single renewal for 16 concurrent calls, the expired session, the missing audience, `GLUONIFY_ZONE`, discovery unreachable or in clear text, the messages.
