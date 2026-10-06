# CLAUDE.md : Gluonify (contexte partagé par tous les dépôts gluonify-*)

PaaS minimaliste (esprit OpenShift) : déploie des **applications Quarkus natives** sur des nœuds Debian 13, zéro administration, état du cluster répliqué par Raft.
Les documents d'architecture et d'exploitation sont dans **gluonify-codex** (`ARCHITECTURE.md`, `docs/`). Ce fichier résume ce qu'il faut savoir pour continuer le travail.

## Règles des projets

- **README.md est tenu à jour à chaque modification.** Toute évolution qui ajoute, change ou retire une fonctionnalité, une limite, une option de configuration ou un format met à jour dans le même
  changement : la matrice « Ce qui est pris en charge, et ce qui ne l'est pas encore », la section qui détaille la fonctionnalité, le tableau de configuration et `plan.md`.
- Réponses et documentation en français. Ne committer que sur demande explicite, ou sous le mandat permanent ci-dessous.
- **Enchaîner sans s'arrêter.** Quand l'utilisateur a dit de terminer le plan « tout seul » (mandat permanent en vigueur : « termine le plan tout seul », « continue sans me demander quoi faire »), ne jamais finir un
  tour par un point d'étape, une question ou « je continue ensuite » : passer au point suivant de `plan.md` dans le même tour. Ne s'arrêter que si le backlog est vide, ou si une décision appartient vraiment à
  l'utilisateur (donnée manquante, action irréversible ou hors périmètre) ; dans ce cas, poser la question une fois, avec une recommandation.
- **Attendre dans le tour, pas entre deux tours.** Une vérification longue (`mvn clean verify`, `scripts/e2e-cluster.sh`, endurance) se lance en arrière-plan, puis on attend son résultat dans le même tour
  (boucle `until … ; do sleep 30; done` en arrière-plan avec un délai maximal de 7 200 000 ms, relancée si elle expire), sans clore le tour pendant l'attente. Une notification « terminé » d'une tâche de fond ne
  dit que le lancement est fini, pas que les tests ont réussi : lire le fichier de résultat.
- **Commits sous mandat permanent** : après chaque étape, si `mvn clean verify` et `scripts/e2e-cluster.sh` passent tous les deux (même commande, `JAVA_HOME` du JDK Liberica 25 défini), committer et pousser sur
  `main` sans demander ; sinon corriger. Les messages de commit finissent par la ligne d'attribution demandée par l'environnement.
- Ne pas modifier `/Users/a/dev/gluonify-gdown` sans accord car un autre Claude travaille dessus par défaut.


## Les dépôts (côte à côte dans `/Users/a/dev`, espace de travail : `gluonify-plasma/gluonify.code-workspace`)
| Dépôt | Rôle | Anciennement |
|---|---|---|
| gluonify-plasma | POM parent + SDK `gluonify-plasma-sdk` (contrats JSON, `AppSpec`, messages WebSocket) | quarkus-paas/common |
| gluonify-higgs | orchestrateur (control-plane) : API, scheduler, ingress, DNS, ACME, Raft, mise en route de la pile, verrous de fichiers | quarkus-paas/control-plane |
| gluonify-strange | agent de nœud : unités systemd des applications, réseau, passerelle `/distributed/*`, relais des verrous | quarkus-paas/agent |
| gluonify-rup | nœuds de stockage (objets SHA-256, fichiers répliqués, quotas) | quarkus-paas/store |
| gluonify-bottom | démon FUSE en C (libfuse3) : verrous de fichiers entre nœuds | quarkus-paas/fuse |
| gluonify-gdown | base graphe répliquée (Raft). **Ne jamais la modifier depuis un autre dépôt** (autre session) | quarkus-graph-db |
| gluonify-top | vault : configuration et secrets par application, interface web Vue | quarkus-vault |
| gluonify-charm | émetteur de jetons JWT (ES256), OIDC minimal | quarkus-id |
| gluonify-photon | passerelle d'API : domaines personnalisés, routage par chemin, contenu statique, webhooks, transformations (squelette ; spécification dans gluonify-codex/api-management.md) | nouveau |
| gluonify-field | serveur de contenu statique (en-tête X-Gluonify-Site, lit /distributed/std/<uuid>/www ; spécification dans gluonify-codex/api-management.md) | nouveau |
| gluonify-calorimeter | observabilité : journaux et mesures conservés, tableaux de bord, alertes (**à construire** ; github.com/gluonify/gluonify-calorimeter, spécification dans gluonify-codex/observability.md) | nouveau |
| gluonify-source | application de départ pour les clients : service Quarkus 4 d'exemple (REST, Quinoa, Charm, Gdown, Photon), README détaillé en cinq langues (en par défaut) ; github.com/gluonify/gluonify-source (voir gluonify-codex/client-integration.md) | nouveau |
| gluonify-sea | « mer de quarks et gluons » : réservoir des binaires natifs signés, servi sur https://images.gluonify.io (lecture publique de la disposition d'amorçage, dépôt authentifié par jeton charm, vérification Ed25519 et anti-retour en arrière) ; github.com/gluonify/gluonify-sea | nouveau |
| gluonify-gauge | pilote pour IA : serveur MCP au-dessus des API de higgs et de bup, avec les garde-fous du client (**à construire** ; spécification dans gluonify-codex/gauge.md) | nouveau |
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
- Tests de bout en bout (Docker, conteneurs Debian 13 + systemd) depuis `gluonify-chamber` : `scripts/e2e-stack.sh <dist> <dist>` (73 contrôles), `e2e-stack-ha.sh` (19), `e2e-store.sh` (42), `e2e-vfs.sh` (28), `e2e-locks.sh` (18), `e2e-recover.sh` (14), `e2e-ingress.sh` (10), `e2e-domains.sh` (15 : domaines personnalisés, ACME HTTP-01/DNS-01 contre Pebble), `e2e-photon.sh` (56 : webhooks de photon en natif sur un cluster réel ; besoin des binaires natifs strange, higgs, rup et photon), `e2e-photon-ha.sh` (21 : 2 réplicas sur 2 nœuds, meneur gelé, basculement), `e2e-field.sh` (47 : field natif sur un cluster réel, besoin des binaires natifs strange, higgs, rup et field dans `<dist>`), `e2e-gateway-stack.sh` (20 : cluster avec stockage ET pile réelle ; field avec un jeton charm par site, photon avec une cible gdown réelle ; binaires strange, higgs, rup, photon, field dans `<dist>`, pile dans le second dossier, sites construits), `e2e-gdown-exposure.sh` (20 : pile réelle gdown+charm+vault, contrat gdown exposé par une route `kind: gdown` avec jeton charm, rôles, 429 ; binaires strange et higgs dans `<dist>`, pile dans le second dossier) ; `smoke-x86_64.sh` pour x86_64 sous émulation.
- Tests unitaires de référence : plasma 21, higgs 188, field 40, photon 94, strange 98, rup 14, bup 60, top 74, charm 25, probe 2.

## Pièges connus (à ne pas redécouvrir)
- **Rename sur le VFS (`/distributed/*`, rclone)** : ne jamais renommer un dossier dont les fichiers viennent d'être écrits (encore en attente d'envoi) : le cache de rclone s'embrouille et les lectures suivantes se bloquent ~30 s. Écrire directement à l'emplacement final et publier par un fichier marqueur (voir `gluonify-field`, `SiteArchive`). Un corps de requête HTTP doit être lu jusqu'au bout, sinon la connexion réutilisée par l'ingress se bloque.
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
- **Commit et push après chaque passe de tests réussie** (tests du dépôt modifié ; messages sobres) ; jamais de push si un test échoue ; ne jamais modifier `gluonify-gdown`.
- Avant de dire qu'un comportement fonctionne : le vérifier par un e2e réel (les tests unitaires ne voient pas les problèmes natifs).

## État et suite
- Migration vers l'espace GitHub `gluonify` faite (commit unique par dépôt, sans historique). Les anciens dépôts `quarkus-*` (dans `/Users/a/dev`) sont intacts, à archiver ; l'outil de migration est dans `/Users/a/dev/gluonify-migration` (hors dépôts).
- **Fait** : `gluonify-photon` : routage par domaine et chemin, certificats ACME par domaine (SNI), politiques de route (adresses, CORS, jeton charm, limites), exposition de gdown (`/api/<base>/<contrat>/` seulement), contenu statique (`gluonify-field`), webhooks (signés, file persistante, rejeu, cible application ou gdown) et transformations (JSON, XML, formulaire, ProtoBuf) ; spécification : `gluonify-codex/api-management.md`. Métriques `gluonify_photon_*` et haute disponibilité de la livraison (meneur par battements de cœur, au moins une fois) faites. Reste : compteurs de limites partagés entre ingress, secrets et schémas dans le vault, jeton charm par client pour field, 
- Limites documentées : pas de verrous entre nœuds sans `distributedLocks`, pas de mmap partagé entre nœuds, restauration de gdown hors ligne, x86_64 vérifié par test de fumée seulement.

## Ce dépôt (gluonify-plasma)
Parent Maven (`io.gluonify:gluonify-plasma`, pom) + module `sdk` (`gluonify-plasma-sdk`). `gluonify.code-workspace` ouvre tous les dépôts. Contenu du SDK : `io.gluonify.plasma` (`AppSpec`, `Instance`, `DeployRequest`, `ws/Msg`, `store/Rendezvous`, `store/StoreCapability`, `wg/`). Tout changement de contrat JSON se répercute dans higgs, strange, rup (réinstaller plasma avant de recompiler les autres). Ajouter un champ à `AppSpec`/`Instance`/`DeployRequest` : garder des constructeurs de compatibilité (les anciens appels sont nombreux) et mettre à jour `Scheduler` (higgs).
- **Interfaces Vue JS multilingues.** Toute interface Vue JS est multilingue : aucun texte en dur dans les composants, les traductions sont dans des fichiers de langue (anglais par défaut, puis fr, es, it, de comme les sites), avec un sélecteur de langue dont le choix est conservé. Les jeux de tests et les données d'exemple sont en anglais.
