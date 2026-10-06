# Gluonify Plasma — POM parent et SDK

POM parent Maven des composants Gluonify (`io.gluonify:gluonify-plasma` : plateforme Quarkus, versions) et bibliothèque partagée `gluonify-plasma-sdk` (contrats JSON : `AppSpec`, `Instance`, messages WebSocket entre nœuds et control-plane, hachage de rendez-vous, capacités de stockage). Le fichier `gluonify.code-workspace` ouvre dans VS Code tous les dépôts Gluonify (supposés côte à côte).

```
mvn install      # installe le parent et le SDK dans ~/.m2 : prérequis de gluonify-higgs, -strange, -rup
```

Documentation d'ensemble : [gluonify-codex](https://github.com/gluonify/gluonify-codex).

## Amorçage à distance : manifeste signé et catalogue de tâches

`io.gluonify.plasma.release` : `ReleaseManifest` (manifeste de version : version, séquence, expiration, un artefact par composant et architecture, format ligne à ligne relu à l'identique), `ReleaseVerifier` (signature Ed25519, expiration, séquence anti-retour, SHA-256), `ReleaseSigner`
(production et signature, utilisé par les tests ; la production réelle passe par `gluonify-chamber/bin/sign-release.sh`) et `TaskCatalogue` (le **catalogue fermé** de tâches avec ses motifs de paramètres, appliqué par le control-plane et par le nœud). Messages WebSocket additifs : `Msg.Startup` (temps de démarrage d'une instance, joint à `Msg.State.startups` : `prepare`, `listen`, `ready`, `total`, historique borné, compteurs), `Msg.BootstrapHello`,
`BootstrapAccepted`, `BootstrapDone`, `TaskRun`, `TaskProgress`, `TaskResult`, `TaskCancel`. Spécification : [gluonify-codex/docs/BOOTSTRAP.md](https://github.com/gluonify/gluonify-codex/blob/main/docs/BOOTSTRAP.md).

## Mode endormi (scale-to-zero) : contrat

`SleepSpec` (champ additif `sleep` de `AppSpec` et `DeployRequest`, constructeurs de compatibilité conservés) : `{enabled (false), freezeAfterSeconds (60), stopAfterSeconds (900, ou gel + 60 si le gel dépasse 840), wakeTimeoutMs (5000), wake (absent : valeurs du cluster)}` ; `SleepSpec.Wake` `{initialReplicas, additionalWhen {inflightPerReplica, heldRequests, slowWakeMs, ratePerReplica, stepReplicas, cooldownSeconds}}` (champs facultatifs, bornes validées au constructeur, constructeur de compatibilité à quatre arguments) ; messages additifs `Msg.InstanceReportRequest` / `Msg.InstanceReport` (rapport d'une instance, y compris endormie) ; valeurs validées au constructeur (gel 1 à 86400 s, arrêt supérieur au gel, réveil 100 à 60000 ms) ; `AppSpec.sleepEnabled()` ; un `AppSpec` qui active le mode et qui est interne, a des ports supplémentaires, un rôle de nœud ou `distributedLocks` est refusé. Messages additifs : `Msg.Desired.sleeping` (instance -> `frozen` | `stopped`, absente = active : l'état de sommeil voulu des instances du nœud, constructeurs de compatibilité conservés) et `Msg.Slept` (accusé de l'agent : état atteint, succès, cause, durée). Spécification complète : [gluonify-codex/ARCHITECTURE.md](https://github.com/gluonify/gluonify-codex/blob/main/ARCHITECTURE.md), « Mode endormi ».
