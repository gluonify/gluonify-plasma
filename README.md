# Gluonify Plasma — POM parent et SDK

POM parent Maven des composants Gluonify (`io.gluonify:gluonify-plasma` : plateforme Quarkus, versions) et bibliothèque partagée `gluonify-plasma-sdk` (contrats JSON : `AppSpec`, `Instance`, messages WebSocket entre nœuds et control-plane, hachage de rendez-vous, capacités de stockage). Le fichier `gluonify.code-workspace` ouvre dans VS Code tous les dépôts Gluonify (supposés côte à côte).

```
mvn install      # installe le parent et le SDK dans ~/.m2 : prérequis de gluonify-higgs, -strange, -rup
```

Documentation d'ensemble : [gluonify-codex](https://github.com/gluonify/gluonify-codex).
