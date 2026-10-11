# gluonify-plasma-cli : la commande `gluonify`

Jar exécutable autonome (Java 17) pour le poste du développeur : il connecte le poste à un cluster, pour que le SDK (`gluonify-plasma-services`) trouve les services sans configuration.

```
java -jar gluonify-plasma-cli-<version>.jar login gluonify.lan     # alias conseillé : alias gluonify='java -jar …/gluonify-plasma-cli.jar'
gluonify status
gluonify token crystallon                                          # « ok, crystallon at https://adm.gluonify.lan/crystallon (DEVELOPER), expires at … »
gluonify logout
```

| Commande | Effet |
|---|---|
| `login <zone>` | lit la découverte `https://<zone>/.well-known/gluonify` puis la configuration OpenID de Charm, ouvre le navigateur sur `/authorize` (client public `gluonify-cli`, **PKCE S256**, `state`), reçoit le code sur un serveur local à usage unique `http://127.0.0.1:<port libre>/callback` (RFC 8252), vérifie `state` et `iss`, échange le code, écrit `~/.gluonify/tokens/<zone>.json` et `~/.gluonify/config.json` (0600). Options : `--no-browser` (adresse affichée seulement), `--port <n>` (port fixe), `--ca <pem>` (autorité locale, gardée pour la zone), `--discovery <url>`, `--scope <portées>` (défaut `openid`), `--timeout <s>` (défaut 300) |
| `logout [<zone>]` | révoque au mieux le jeton de rafraîchissement chez Charm, efface les jetons et la zone |
| `status` | zones, zone courante (`*`), personne, expirations, audiences ; jamais un jeton |
| `token <service> [--print]` | résout le service exactement comme le SDK et obtient un jeton (renouvelé au besoin) ; n'affiche le jeton qu'avec `--print` |

Aucun mot de passe ne passe par la ligne de commande : la connexion se fait dans le navigateur, chez Charm. Codes de sortie : 0, 1 (échec, message en anglais qui dit quoi faire), 2 (usage).

Côté cluster (hors de ce dépôt) : Higgs publie `/.well-known/gluonify`, Charm enregistre le client public `gluonify-cli` (PKCE, jetons de rafraîchissement, audiences des services, portée `openid`) et accepte le port variable du bouclage local (RFC 8252 § 7.3 ; aujourd'hui Charm compare `redirect_uri` caractère pour caractère : en attendant, `--port` fixe un port enregistré).

`mvn clean verify` : 6 tests contre le faux Charm de `FakeCluster` (connexion PKCE de bout en bout avec un faux navigateur qui suit la redirection, `state` falsifié, `error=access_denied`, délai, découverte indisponible ou en clair, usage).
