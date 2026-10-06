# Gluonify Plasma — POM parent et SDK

POM parent Maven des composants Gluonify (`io.gluonify:gluonify-plasma` : plateforme Quarkus, versions) et bibliothèque partagée `gluonify-plasma-sdk` (contrats JSON : `AppSpec`, `Instance`, messages WebSocket entre nœuds et control-plane, hachage de rendez-vous, capacités de stockage). Le fichier `gluonify.code-workspace` ouvre dans VS Code tous les dépôts Gluonify (supposés côte à côte).

```
mvn install      # installe le parent et le SDK dans ~/.m2 : prérequis de gluonify-higgs, -strange, -rup
```

Documentation d'ensemble : [gluonify-codex](https://github.com/gluonify/gluonify-codex).

## Amorçage à distance : manifeste signé et catalogue de tâches

`io.gluonify.plasma.release` : `ReleaseManifest` (manifeste de version : version, séquence, expiration, un artefact par composant et architecture, format ligne à ligne relu à l'identique), `ReleaseVerifier` (signature Ed25519, expiration, séquence anti-retour, SHA-256), `ReleaseSigner`
(production et signature, utilisé par les tests ; la production réelle passe par `gluonify-chamber/bin/sign-release.sh`) et `TaskCatalogue` (le **catalogue fermé** de tâches avec ses motifs de paramètres, appliqué par le control-plane et par le nœud). Messages WebSocket additifs : `Msg.BootstrapHello`,
`BootstrapAccepted`, `BootstrapDone`, `TaskRun`, `TaskProgress`, `TaskResult`, `TaskCancel`. Spécification : [gluonify-codex/docs/BOOTSTRAP.md](https://github.com/gluonify/gluonify-codex/blob/main/docs/BOOTSTRAP.md).
