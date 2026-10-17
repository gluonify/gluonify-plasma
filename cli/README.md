# gluonify-plasma-cli: the `gluonify` command

Standalone executable jar (Java 17) for the developer's workstation: it connects the workstation to a cluster, so that the SDK (`gluonify-plasma-services`) finds the services without any configuration.

```
java -jar gluonify-plasma-cli-<version>.jar login gluonify.lan     # recommended alias: alias gluonify='java -jar …/gluonify-plasma-cli.jar'
gluonify status
gluonify token crystallon                                          # "ok, crystallon at https://adm.gluonify.lan/crystallon (DEVELOPER), expires at …"
gluonify logout
| Command | Effect |

| Commande | Effet |
|---|---|
| `login <zone>` | reads the discovery document `https://<zone>/.well-known/gluonify`, then the OpenID configuration of Charm, opens the browser on `/authorize` (public client `gluonify-cli`, **PKCE S256**, `state`), receives the code on a single-use local server `http://127.0.0.1:<free port>/callback` (RFC 8252), checks `state` and `iss`, exchanges the code, writes `~/.gluonify/tokens/<zone>.json` and `~/.gluonify/config.json` (0600). Options: `--no-browser` (only prints the address), `--port <n>` (fixed port), `--ca <pem>` (local authority, kept for the zone), `--discovery <url>`, `--scope <scopes>` (default `openid`), `--timeout <s>` (default 300) |
| `logout [<zone>]` | revokes the refresh token at Charm on a best-effort basis, deletes the tokens and the zone |
| `status` | zones, current zone (`*`), person, expiry times, audiences; never a token |
| `token <service> [--print]` | resolves the service exactly like the SDK and obtains a token (renewed if needed); prints the token only with `--print` |

No password goes through the command line: sign-in happens in the browser, at Charm. Exit codes: 0, 1 (failure, message in English that says what to do), 2 (usage).

Cluster side (outside this repository): Higgs publishes `/.well-known/gluonify`, Charm registers the public client `gluonify-cli` (PKCE, refresh tokens, service audiences, scope `openid`) and accepts the variable loopback port (RFC 8252 § 7.3; today Charm compares `redirect_uri` character for character: until then, `--port` fixes a registered port).

`mvn clean verify`: 6 tests against the fake Charm of `FakeCluster` (end-to-end PKCE sign-in with a fake browser that follows the redirect, forged `state`, `error=access_denied`, timeout, discovery unavailable or in clear text, usage).
