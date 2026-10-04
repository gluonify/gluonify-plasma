# CLAUDE.md : Gluonify (contexte partagé par tous les dépôts gluonify-*)

PaaS minimaliste (esprit OpenShift) : déploie des **applications Quarkus natives** sur des nœuds Debian 13, zéro administration, état du cluster répliqué par Raft.
Les documents d'architecture et d'exploitation sont dans **gluonify-codex** (`ARCHITECTURE.md`, `docs/`). Ce fichier résume ce qu'il faut savoir pour continuer le travail.

## Les dépôts (côte à côte dans `/Users/a/dev`, espace de travail : `gluonify-plasma/gluonify.code-workspace`)
| Dépôt | Rôle | Anciennement |
|---|---|---|
| gluonify-plasma | POM parent + SDK `gluonify-plasma-sdk` (contrats JSON, `AppSpec`, messages WebSocket) | quarkus-paas/common |
| gluonify-higgs | control-plane : API, scheduler, ingress, DNS, ACME, Raft, mise en route de la pile, verrous de fichiers | quarkus-paas/control-plane |
| gluonify-strange | agent de nœud : unités systemd des applications, réseau, passerelle `/distributed/*`, relais des verrous | quarkus-paas/agent |
| gluonify-rup | nœuds de stockage (objets SHA-256, fichiers répliqués, quotas) | quarkus-paas/store |
| gluonify-bottom | démon FUSE en C (libfuse3) : verrous de fichiers entre nœuds | quarkus-paas/fuse |
| gluonify-gdown | base graphe répliquée (Raft). **Ne jamais la modifier depuis un autre dépôt** (autre session) | quarkus-graph-db |
| gluonify-top | vault : configuration et secrets par application, interface web Vue | quarkus-vault |
| gluonify-charm | émetteur de jetons JWT (ES256), OIDC minimal | quarkus-id |
| gluonify-photon | passerelle d'API : domaines personnalisés, routage par chemin, contenu statique, webhooks, transformations (squelette ; spécification dans gluonify-codex/api-management.md) | nouveau |
| gluonify-bup | builder : clone Git, build natif, publication, déploiement | quarkus-paas-builder |
| gluonify-probe | application de test (cible du builder) | quarkus-app-blank |
| gluonify-chamber | scripts e2e, installateur Debian, unités systemd, `bin/build-all.sh` | quarkus-paas/scripts, node-image |
| gluonify-codex | documentation et architecture | quarkus-paas/docs, README, ARCHITECTURE |

Nommage : groupId `io.gluonify.<x>` = package Java racine, artifactId `gluonify-<x>`. Le terme `paas` des chemins, unités, propriétés, variables d'environnement et métriques est
`gluonify` (`/etc/gluonify`, `gluonify-higgs.service`, `gluonify.*`, `GLUONIFY_*`, `gluonify_*`, audience `gluonify-api`, realm `gluonify`). Le nom commun « PaaS » reste.

## Environnement et commandes
- Java 25, Quarkus 4.0.0.Beta1, GraalVM natif partout (NIK 25), Jackson 3 (`tools.jackson`), WebSockets Next = transport standard. `export JAVA_HOME=$(/usr/libexec/java_home -v 25)`.
- **Ordre de build** : `gluonify-plasma` d'abord (`mvn install`, installe parent + SDK dans `~/.m2`), puis les autres (`mvn package`, `-Pnative` pour le natif).
- Natifs par Docker : `docker build --build-context plasma=../gluonify-plasma --target out --output type=local,dest=dist -f Dockerfile.build .` dans chaque dépôt ; tous à la fois : `gluonify-chamber/bin/build-all.sh <dist>` (gdown : variable `GDOWN_DIR`).
- Tests de bout en bout (Docker, conteneurs Debian 13 + systemd) depuis `gluonify-chamber` : `scripts/e2e-stack.sh <dist> <dist>` (73 contrôles), `e2e-stack-ha.sh` (19), `e2e-store.sh` (42), `e2e-vfs.sh` (28), `e2e-locks.sh` (18), `e2e-recover.sh` (14), `e2e-ingress.sh` (10) ; `smoke-x86_64.sh` pour x86_64 sous émulation.
- Tests unitaires de référence : plasma 21, higgs 132, strange 98, rup 14, bup 60, top 74, charm 25, probe 2.

## Pièges connus (à ne pas redécouvrir)
- **Natif** : tout type (dé)sérialisé par Jackson doit figurer dans la liste de réflexion du composant (`Reflection.java` et `META-INF/native-image/io.gluonify.<x>/…/reachability-metadata.json`) ; un oubli ne casse qu'en natif (état Raft non relu au redémarrage). `HttpClient` créé paresseusement dans les beans injectés dans des filtres ; `Boolean` et non `boolean` dans les instantanés Raft.
- **Natif arm64** : les appels montants FFM de GraalVM 25 plantent (d'où le démon FUSE en C). `MemorySegment.fill` plante aussi.
- **Sous émulation x86_64** (qemu-user) : enregistrer qemu (`docker run --privileged --rm tonistiigi/binfmt --install amd64`, il se perd), construire avec `MAVEN_OPTS=-Djdk.lang.Process.launchMechanism=VFORK` ; systemd ne tourne pas sous qemu-user (d'où `smoke-x86_64.sh`).
- **POST avec corps dans l'agent** : utiliser une ressource JAX-RS ; une route `Router` n'est pas appelée avec le corps.
- **WebSocket vers gdown** : `quarkus.websockets-next.client.max-frame-size=4194304` (sinon les gros messages fragmentés bloquent la connexion) et `max-message-size=83886080`.
- **gdown garde ses données en mémoire** (≈ 5 fois le volume de fichiers stockés dans `/distributed/graph`) : défaut 768 Mo (`gluonify.stack.graph-db-memory-mb`) ; sauvegardes : les fichiers n'existent qu'après un point de contrôle.
- **e2e** : un disque Docker plein (« No space left on device ») fait échouer des scripts sans rapport (`docker builder prune`). Dans les laboratoires, `/tmp` est un tmpfs (utiliser `/var/tmp` pour `docker cp`). `pkill -f` se tue lui-même sans crochets dans le motif. macOS : `sed -i ''`, pas de `timeout`.
- Le contrôle « au retour du nœud, la réparation du control-plane lui recopie le fichier » de `e2e-vfs.sh` est **intermittent** (replacement après plus de 30 s d'arrêt du nœud de stockage) ; rejouer avant de chercher un bug.
- Changer `distributedLocks` d'une application n'est pris en compte qu'au redémarrage (`POST /apps/{nom}/redeploy`) ; le quota (`distributedQuotaMb`) s'applique sans redémarrage.

## Conventions de travail
- Documentation et commentaires en **français** ; messages de commit sobres, terminés par `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Ne pas pousser sans demande explicite ; ne jamais modifier `gluonify-gdown`.
- Avant de dire qu'un comportement fonctionne : le vérifier par un e2e réel (les tests unitaires ne voient pas les problèmes natifs).

## État et suite
- Migration vers l'espace GitHub `gluonify` faite (commit unique par dépôt, sans historique). Les anciens dépôts `quarkus-*` (dans `/Users/a/dev`) sont intacts, à archiver ; l'outil de migration est dans `/Users/a/dev/gluonify-migration` (hors dépôts).
- En cours : `gluonify-photon` (squelette créé ; passerelle d'API : domaines personnalisés, routage par chemin, site statique, boîte à webhooks, transformations ; cadrage dans `quarkus-paas/api-management.md`, non commité) ; ajouter photon à `branding.md` et au README du codex.
- Limites documentées : pas de verrous entre nœuds sans `distributedLocks`, pas de mmap partagé entre nœuds, restauration de gdown hors ligne, x86_64 vérifié par test de fumée seulement.

## Ce dépôt (gluonify-plasma)
Parent Maven (`io.gluonify:gluonify-plasma`, pom) + module `sdk` (`gluonify-plasma-sdk`). `gluonify.code-workspace` ouvre tous les dépôts. Contenu du SDK : `io.gluonify.plasma` (`AppSpec`, `Instance`, `DeployRequest`, `ws/Msg`, `store/Rendezvous`, `store/StoreCapability`, `wg/`). Tout changement de contrat JSON se répercute dans higgs, strange, rup (réinstaller plasma avant de recompiler les autres). Ajouter un champ à `AppSpec`/`Instance`/`DeployRequest` : garder des constructeurs de compatibilité (les anciens appels sont nombreux) et mettre à jour `Scheduler` (higgs).
