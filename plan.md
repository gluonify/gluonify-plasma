# plan.md : gluonify-plasma (SDK + racine)

## Fait récemment
- [x] `Requires` : dépendances système d'un artefact par distribution (`debian13`…), noms validés.
- [x] Protocole de gluonify-fermion (`io.gluonify.plasma.fermion` : `FermionMsg`, descripteurs de compétences et de workflows), 3 tests.
- [x] `GzipSignature` : signature par fichier dans l'en-tête gzip (RFC 1952, FEXTRA « GL »), vérifiable hors ligne, en plus du manifeste.
- [x] `ReleaseManifest.MAX_ARTIFACTS` porté de 64 à 1024 ; taille maximale du manifeste harmonisée (256 Kio) entre sea et strange.

- [x] Module `fermion-client` : client WebSocket de fermion (reconnexion, file bornée, registre de compétences), 10 tests.

## Pas encore fait
- [ ] Client de fermion : vérifier en natif et contre un vrai fermion (e2e) ; le module est fait (tests unitaires seulement).
- [ ] `install.sh` (shell, hors du SDK) ne lit pas la signature par fichier : reste sur le manifeste, qui suffit.
- [ ] Signature de fichier non exigée par défaut par les vérificateurs (sea, strange) : à activer une fois les anciennes publications remplacées.
- [ ] Signature croisée par deux éditeurs (deux clés de publication valides en permanence) à la rotation de clé.

## Ménage (non fonctionnel)
- [ ] `gluonify.code-workspace` modifié et `claude_conversation.txt` à la racine : fichiers de session à trier ou committer, pas encore faits par l'utilisateur.
