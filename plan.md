# plan.md : gluonify-plasma (SDK + racine)

## Fait récemment
- [x] `GzipSignature` : signature par fichier dans l'en-tête gzip (RFC 1952, FEXTRA « GL »), vérifiable hors ligne, en plus du manifeste.
- [x] `ReleaseManifest.MAX_ARTIFACTS` porté de 64 à 1024 ; taille maximale du manifeste harmonisée (256 Kio) entre sea et strange.

## Pas encore fait
- [ ] `install.sh` (shell, hors du SDK) ne lit pas la signature par fichier : reste sur le manifeste, qui suffit.
- [ ] Signature de fichier non exigée par défaut par les vérificateurs (sea, strange) : à activer une fois les anciennes publications remplacées.
- [ ] Signature croisée par deux éditeurs (deux clés de publication valides en permanence) à la rotation de clé.

## Ménage (non fonctionnel)
- [ ] `gluonify.code-workspace` modifié et `claude_conversation.txt` à la racine : fichiers de session à trier ou committer, pas encore faits par l'utilisateur.
