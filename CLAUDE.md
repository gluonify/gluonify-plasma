# CLAUDE.md : gluonify-plasma

Contexte commun à tous les dépôts (règles, dépôts, build, pièges, conventions, modèle Edge) : `/Users/a/dev/gluonify/CLAUDE.md`, chargé automatiquement avec ce fichier.

## Ce dépôt (gluonify-plasma)
Parent Maven (`io.gluonify:gluonify-plasma`, pom) + module `sdk` (`gluonify-plasma-sdk`). `gluonify.code-workspace` ouvre tous les dépôts. Contenu du SDK : `io.gluonify.plasma` (`AppSpec`, `Instance`, `DeployRequest`, `ws/Msg`, `store/Rendezvous`, `store/StoreCapability`, `wg/`). Tout changement de contrat JSON se répercute dans higgs, strange, rup (réinstaller plasma avant de recompiler les autres). Ajouter un champ à `AppSpec`/`Instance`/`DeployRequest` : garder des constructeurs de compatibilité (les anciens appels sont nombreux) et mettre à jour `Scheduler` (higgs).
