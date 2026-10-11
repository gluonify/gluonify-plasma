# Gluonify Plasma — POM parent, SDK et clients

POM parent Maven des composants Gluonify (`io.gluonify.plasma:gluonify-plasma` : plateforme Quarkus, versions) et bibliothèques partagées, trois modules :

| Module (`io.gluonify.plasma:…`) | Contenu | Java |
|---|---|---|
| `gluonify-plasma-sdk` (`sdk/`) | contrats JSON : `AppSpec`, `Instance`, messages WebSocket entre nœuds et control-plane, hachage de rendez-vous, capacités de stockage, manifeste signé, protocole de fermion, records de Crystallon (`io.gluonify.plasma.crystallon`) | 17 |
| `gluonify-plasma-fermion-client` (`fermion-client/`) | client WebSocket de gluonify-fermion (Quarkus, CDI) | 25 |
| `gluonify-plasma-crystallon-client` (`crystallon-client/`) | client HTTP de gluonify-crystallon : rendre en PDF un modèle par son nom ou son identifiant, ou un modèle envoyé avec la demande (`inline`) ; sans CDI, Jackson 2 ou 3 détecté à l'exécution ; [README](crystallon-client/README.md) | 17 |

`sdk` et `crystallon-client` sont les modules publiés pour les applications des clients (dépôt Maven `https://images.gluonify.io/maven`) : ils se compilent pour Java 17 (`maven.compiler.release` 17), le parent et les autres modules pour Java 25. La version `0.1.0-SNAPSHOT` du dépôt est fixée à la publication (elle suit celle de la plateforme). Le fichier `gluonify.code-workspace` ouvre dans VS Code tous les dépôts Gluonify (supposés côte à côte).

```
mvn install      # installe le parent, le SDK et les clients dans ~/.m2 : prérequis de gluonify-higgs, -strange, -rup
```

Documentation d'ensemble : [gluonify-codex](https://github.com/gluonify/gluonify-codex).

## Amorçage à distance : manifeste signé et catalogue de tâches

`io.gluonify.plasma.release` : `ReleaseManifest` (manifeste de version : version, séquence, expiration, un artefact par composant et architecture, format ligne à ligne relu à l'identique), `ReleaseVerifier` (signature Ed25519, expiration, séquence anti-retour, SHA-256), `ReleaseSigner`
(production et signature, utilisé par les tests ; la production réelle passe par `gluonify-chamber/bin/sign-release.sh`) et `Gzip` (fichiers gzip des versions : règle `.gz` hors `.tar.gz` = fichier unique compressé, nom décompressé sans `.gz` ; `gunzip` en flux borné par un plafond de taille et un rapport de décompression de 100, CRC-32, taille et octets en trop vérifiés ; le gzip ne remplace pas le SHA-256 du manifeste, vérifié d'abord sur le `.gz`) ; `ReleaseManifest.Artifact.gzipped()/plainFile()` ; le format du manifeste est inchangé (aucun champ ajouté, les noms en `.gz` sont des noms simples valides) ; `ReleaseSigner.build` reconnaît `<composant>-linux-<arch>.gz` ; et `TaskCatalogue` (le **catalogue fermé** de tâches avec ses motifs de paramètres, appliqué par le control-plane et par le nœud). Rotation de la clé de publication : `KeyRollover` (annonce `rollover.json` de la nouvelle clé, format canonique, séparée du manifeste dont le format 0.1.0 reste inchangé) et `TrustedKeys` (ensemble de clés acceptées, fichier `release.keys`, règles d'acceptation d'une annonce `applyRollover`, fin de rotation `finish`, refus après la date de bascule) ; tâche `rotate-release-key` du catalogue. Messages WebSocket additifs : `Msg.Startup` (temps de démarrage d'une instance, joint à `Msg.State.startups` : `prepare`, `listen`, `ready`, `total`, historique borné, compteurs), `Msg.BootstrapHello`,
`BootstrapAccepted`, `BootstrapDone`, `TaskRun`, `TaskProgress`, `TaskResult`, `TaskCancel`. Spécification : [gluonify-codex/docs/BOOTSTRAP.md](https://github.com/gluonify/gluonify-codex/blob/main/docs/BOOTSTRAP.md).

## Mode endormi (scale-to-zero) : contrat

`SleepSpec` (champ additif `sleep` de `AppSpec` et `DeployRequest`, constructeurs de compatibilité conservés) : `{enabled (false), freezeAfterSeconds (60), stopAfterSeconds (900, ou gel + 60 si le gel dépasse 840), wakeTimeoutMs (5000), wake (absent : valeurs du cluster)}` ; `SleepSpec.Wake` `{initialReplicas, additionalWhen {inflightPerReplica, heldRequests, slowWakeMs, ratePerReplica, stepReplicas, cooldownSeconds}}` (champs facultatifs, bornes validées au constructeur, constructeur de compatibilité à quatre arguments) ; messages additifs `Msg.InstanceReportRequest` / `Msg.InstanceReport` (rapport d'une instance, y compris endormie) ; valeurs validées au constructeur (gel 1 à 86400 s, arrêt supérieur au gel, réveil 100 à 60000 ms) ; `AppSpec.sleepEnabled()` ; un `AppSpec` qui active le mode et qui est interne, a des ports supplémentaires, un rôle de nœud ou `distributedLocks` est refusé. Messages additifs : `Msg.Desired.sleeping` (instance -> `frozen` | `stopped`, absente = active : l'état de sommeil voulu des instances du nœud, constructeurs de compatibilité conservés) et `Msg.Slept` (accusé de l'agent : état atteint, succès, cause, durée). Spécification complète : [gluonify-codex/ARCHITECTURE.md](https://github.com/gluonify/gluonify-codex/blob/main/ARCHITECTURE.md), « Mode endormi ».

## Organisation (le client) : contrat

`AppSpec.organization` et `DeployRequest.organization` (champ additif, chaîne facultative, constructeurs de compatibilité conservés) : l'organisation à laquelle appartient l'application, `[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?` (étiquette DNS de 1 à 40 caractères, le motif de gluonify-charm), validée au constructeur. Absente (`null`, champ omis dans le JSON), l'application n'est rattachée à personne : les lecteurs l'affichent `_unassigned` (`Organizations.UNASSIGNED`, `AppSpec.organizationOrUnassigned()`), valeur qui n'est jamais enregistrée (le motif la refuse). Un état ou une requête sans le champ se relit à l'identique. `AppSpec.withOrganization(org)` copie la spécification. Dans `DeployRequest`, `organization: "_unassigned"` demande le retrait (`toSpec` donne alors une spécification sans organisation) : le control-plane distingue ainsi « absente » (organisation enregistrée conservée) de « retirée ». Seul un administrateur de la plateforme fixe l'organisation (règle appliquée par gluonify-higgs) ; décision : [gluonify-codex/metering.md](https://github.com/gluonify/gluonify-codex/blob/main/metering.md) § 2.2.

`io.gluonify.plasma.Organizations` : `UNASSIGNED`, `CLAIM` (`org`, revendication des jetons Charm), `PATTERN`, `isValid`, `requireValid`, `orUnassigned`, `matches(filtre, org)` (filtre `null` : tout ; `_unassigned` : sans organisation) et les quatre **rôles des clients**, valables dans leur organisation seulement et jamais sous le préfixe réservé `gluonify:` :

| Rôle | Droits |
|---|---|
| `org:owner` | tout, accès et facturation compris |
| `org:developer` | dépôts, builds, déploiements, configuration, DNS et API |
| `org:viewer` | lecture seule |
| `org:billing` | consommation et factures |

## Protocole de gluonify-fermion (workflows BPMN et règles DMN)

Paquet `io.gluonify.plasma.fermion` : le contrat WebSocket entre un nœud applicatif et gluonify-fermion. `FermionMsg` (interface scellée, champ `t`) : `Auth` (premier message : jeton Charm d'audience `gluonify-fermion` et version du protocole, jamais dans l'URL), `Hello` (compétences et workflows utilisés, adresse de l'OpenAPI), `Start` (soumettre un workflow), `Command` (exécuter une tâche), `Result` (succès ou échec avec `errorKind` lisible par la machine), `Error`. Types associés : `SkillDescriptor`, `WorkflowDescriptor`, `SkillParam`, `Translation`, `Language` (en, fr, es, it, de). Le SDK ne contient que le contrat ; le client est le module `fermion-client` (`gluonify-plasma-fermion-client`, paquet `io.gluonify.plasma.fermion.client`) : connexion Quarkus WebSockets Next avec jeton Charm dans la trame `Auth`, reconnexion planifiée avec un connecteur neuf à chaque tentative, file bornée des `Start` pendant une coupure, SPI `SkillHandler` / `SkillHandlerRegistry` / `TaskContext` / `WorkflowUsage` ; voir `fermion-client/README.md` (configuration `gluonify.fermion.client.*`). Les types doivent être listés dans le `Reflection.java` du consommateur (natif).

## Contrat de gluonify-crystallon : records et client

Paquet `io.gluonify.plasma.crystallon` (SDK, Java 17) : `RenderParams` (`lang`, `country`, `currency`, `timezone`, `dateStyle`, `numberStyle` ; `null` = défaut du serveur, omis du JSON), `RenderRequest` (`data`, jamais `null` : au minimum `{}`, sinon le serveur rendrait l'exemple du modèle ; `params`), `InlineRenderRequest` (`template` : le nom d'un modèle prédéfini ou un objet, jamais `null` ; `data`, `params` comme ci-dessus : corps de `POST /v1/render` et `/v1/render/validate`), `Issue` (`code`, `message`, `path`), `Check` (réponse de `…/render/validate` : `valid`, `pages`, `warnings`, `errors`, `warningDetails`, listes jamais `null`) et `ErrorBody` (`code`, `message`, `path`). Les types lus ignorent les propriétés inconnues.

Le client est le module `crystallon-client` (`gluonify-plasma-crystallon-client`, paquet `io.gluonify.plasma.crystallon.client`) : `CrystallonClient` (`builder()`, `fromEnvironment()`, `byId`, `byName`, `inline`), `TemplateRef` (`version(n)`, `render`, `validate`), `InlineTemplate` (`render`, `validate` d'un modèle envoyé avec la demande), `RenderOptions`, `RenderedPdf`, `CrystallonTokens` (`none`, `fixed`, `file`, `forwarding`, `fromEnvironment`), `PayloadCodec` (`detect()` : Jackson 3 d'abord, sinon Jackson 2, `Jackson3Codec.of` / `Jackson2Codec.of` pour le mapper de l'application) et `CrystallonException` (`status`, `code`, `path`). Aucune dépendance obligatoire hors du SDK, ni CDI ; voir `crystallon-client/README.md` (dépendance, configuration, jetons, erreurs, image native : les types du payload sont à la charge de l'application). Le code Java généré par l'onglet « Code » de l'éditeur de Crystallon appelle exactement ces signatures.
