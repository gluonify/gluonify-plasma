# plan.md : gluonify-plasma (SDK + racine)

## Fait récemment
- [x] `Requires` : dépendances système d'un artefact par distribution (`debian13`…), noms validés.
- [x] Protocole de gluonify-fermion (`io.gluonify.plasma.fermion` : `FermionMsg`, descripteurs de compétences et de workflows), 3 tests.
- [x] `GzipSignature` : signature par fichier dans l'en-tête gzip (RFC 1952, FEXTRA « GL »), vérifiable hors ligne, en plus du manifeste.
- [x] `ReleaseManifest.MAX_ARTIFACTS` porté de 64 à 1024 ; taille maximale du manifeste harmonisée (256 Kio) entre sea et strange.

- [x] Module `fermion-client` : client WebSocket de fermion (reconnexion, file bornée, registre de compétences), 10 tests.
- [x] Records de Crystallon dans le SDK (`io.gluonify.plasma.crystallon` : `RenderParams`, `RenderRequest`, `Issue`, `Check`, `ErrorBody`) ; `sdk` compilé et testé en Java 17 (`maven.compiler.release` 17, vérifié aussi sous un JDK 17).
- [x] Module `crystallon-client` (`gluonify-plasma-crystallon-client`) : client HTTP de Crystallon en Java 17 pur, sans CDI, Jackson 2 ou 3 détecté à l'exécution, jetons (`none`, `fixed`, `file`, `forwarding`, `fromEnvironment`), erreurs `CrystallonException`, PDF borné ; 63 tests (56 + 3 + 4) sous cinq chemins de classes (Jackson 2 et 3, 3 seul, 2 seul, 2 sans jsr310, aucun) contre un faux serveur `HttpServer` ; essai en natif (NIK 25, Jackson 3 puis Jackson 2) réussi avec un programme d'essai.

## Pas encore fait
- [ ] Client de Crystallon : vérifier dans une vraie application Quarkus native (le programme d'essai natif seul est fait) et contre un vrai Crystallon (e2e du cluster de développement) ; tests unitaires et essai natif seulement pour l'instant.
- [ ] Publication de `sdk` et `crystallon-client` sur `https://images.gluonify.io/maven` : fixer la version de la plateforme (retirer `-SNAPSHOT`), publier aussi le POM parent (les deux modules y pointent) ; `mvn dependency:get` depuis une machine sans `~/.m2` pour vérifier.
- [ ] `CrystallonTokens.platform()` : jeton d'application vers service (`client_credentials` de Charm, autorisé par Higgs selon `uses`, injecté par Strange), quand la plateforme l'émettra.
- [ ] Module `crystallon-client-quarkus` (producteur CDI, mapper et jeton de la requête) seulement si plusieurs applications répètent la classe donnée dans le README.
- [ ] Client de fermion : vérifier en natif et contre un vrai fermion (e2e) ; le module est fait (tests unitaires seulement).
- [ ] `install.sh` (shell, hors du SDK) ne lit pas la signature par fichier : reste sur le manifeste, qui suffit.
- [ ] Signature de fichier non exigée par défaut par les vérificateurs (sea, strange) : à activer une fois les anciennes publications remplacées.
- [ ] Signature croisée par deux éditeurs (deux clés de publication valides en permanence) à la rotation de clé.

## Ménage (non fonctionnel)
- [ ] `gluonify.code-workspace` modifié et `claude_conversation.txt` à la racine : fichiers de session à trier ou committer, pas encore faits par l'utilisateur.
