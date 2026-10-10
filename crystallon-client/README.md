# gluonify-plasma-crystallon-client

Client HTTP de **gluonify-crystallon** (documents PDF depuis un modèle stocké) pour les applications des clients : on désigne un modèle par son nom ou son identifiant, on passe un payload (record, bean ou `Map`), on reçoit le PDF.

- **Java 17**, sans CDI, sans Quarkus : une seule dépendance obligatoire, le SDK de la plateforme (`gluonify-plasma-sdk`, transitif).
- **Jackson 2 ou Jackson 3**, celui de l'application, détecté à l'exécution (`PayloadCodec.detect()`) : aucun des deux n'est transitif.
- Transport `java.net.http.HttpClient` créé au premier appel (compatible image native), délai, taille maximale du PDF, jeton Charm.
- Le code que l'onglet « Code » de l'éditeur de Crystallon génère (records du payload et façade `<Nom>Document`) s'appuie sur ce module.

## Dépendance

La version est celle de la plateforme (`0.6.0` à la rédaction ; le dépôt porte `0.1.0-SNAPSHOT`, fixée à la publication). Le dépôt Maven est servi par gluonify-sea, en lecture publique.

```xml
<repositories>
  <repository>
    <id>gluonify</id>
    <url>https://images.gluonify.io/maven</url>
  </repository>
</repositories>

<dependency>
  <groupId>io.gluonify.plasma</groupId>
  <artifactId>gluonify-plasma-crystallon-client</artifactId>
  <version>0.6.0</version>
</dependency>
```

```kotlin
// Gradle (Kotlin DSL)
repositories {
    mavenCentral()
    maven("https://images.gluonify.io/maven")
}
dependencies {
    implementation("io.gluonify.plasma:gluonify-plasma-crystallon-client:0.6.0")
}
```

Jackson vient de l'application (Quarkus 4 : Jackson 3). Avec **Jackson 2**, ajouter aussi `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` : sans lui, Jackson 2 refuse les `LocalDate` (le client enregistre le `JavaTimeModule` s'il le trouve). Jackson 2 : 2.15 ou plus (records) ; vérifié avec 2.17.2 et 2.22.2, et Jackson 3 avec 3.1.4.

## Exemple

```java
CrystallonClient crystallon = CrystallonClient.fromEnvironment();

RenderedPdf pdf = crystallon.byName("Gluonify invoice").render(payload);                 // payload : record, bean ou Map
Files.write(Path.of("invoice.pdf"), pdf.bytes());                                         // pdf.pages(), pdf.warnings(), pdf.pdfa()

RenderOptions fr = RenderOptions.builder().lang("fr").country("CH").currency("CHF").timezone("Europe/Zurich").build();
RenderedPdf swiss = crystallon.byId("3f2a…").version(7).render(payload, fr);              // une version précise du modèle

Check check = crystallon.byName("Gluonify invoice").validate(payload);                     // sans PDF : erreurs et avertissements avec leur chemin
if (!check.valid()) check.errors().forEach(e -> log.warn("{} {} ({})", e.code(), e.message(), e.path()));
```

`byName` et `byId` ne font aucun appel réseau : `TemplateRef` est immuable et peut être gardé. Sans `version(n)`, Crystallon rend la version courante du modèle ; avec, la version `n` de son historique (`?version=n`). `RenderOptions.DEFAULT` demande le PDF/A-1B et laisse les paramètres au serveur ; `pdfa(false)` donne le PDF brut. Un payload `null` est envoyé comme `{}` (un corps sans `data` ferait rendre l'exemple du modèle).

## Configuration

`CrystallonClient.fromEnvironment()` lit l'environnement que la plateforme injecte :

| Variable | Rôle |
|---|---|
| `GLUONIFY_SERVICE_CRYSTALLON_URL` | adresse de Crystallon ; injectée quand l'application déclare `crystallon` dans `uses` (appspec). Absente : `IllegalStateException` qui dit quoi faire |
| `GLUONIFY_CRYSTALLON_TOKEN` | le jeton Charm lui-même |
| `GLUONIFY_CRYSTALLON_TOKEN_FILE` | sinon, un fichier qui contient le jeton, relu à chaque appel |

Sans code d'environnement, avec le constructeur :

| Méthode de `CrystallonClient.builder()` | Défaut | Rôle |
|---|---|---|
| `baseUrl(String \| URI)` | obligatoire | adresse `http(s)://…`, préfixe de chemin admis, barre finale ignorée |
| `tokens(CrystallonTokens)` | `none()` | d'où vient le jeton |
| `timeout(Duration)` | 30 s | délai d'un appel, lecture du corps comprise |
| `json(PayloadCodec)` | `PayloadCodec.detect()` | passerelle JSON |
| `httpClient(HttpClient)` | créé au premier appel (HTTP/1.1) | client HTTP à réutiliser |
| `maxPdfBytes(long)` | 50 Mo | au-delà, le PDF est refusé et la lecture s'arrête |

## Jetons

Crystallon exige un jeton Charm d'audience `gluonify-crystallon` ; le rôle `gluonify:crystallon:render` suffit pour rendre et valider (`…/render`, `…/render/validate`). Les rôles `convert` (conversion et fusion) et `templates` (gestion des modèles) ne servent pas ici.

| `CrystallonTokens` | Comportement |
|---|---|
| `none()` | pas d'en-tête `Authorization` |
| `fixed(token)` | jeton fixe (vide : `IllegalArgumentException`) |
| `file(path)` | lu **à chaque appel**, espaces de début et de fin retirés ; fichier absent ou vide : pas de jeton ; illisible : `CrystallonException` (statut 0) |
| `forwarding(supplier)` | le jeton de la personne connectée, demandé à chaque appel (`null` ou vide : pas de jeton) |
| `fromEnvironment()` | `GLUONIFY_CRYSTALLON_TOKEN`, sinon `GLUONIFY_CRYSTALLON_TOKEN_FILE`, sinon `none()` ; `fromEnvironment(Function<String,String>)` lit une autre source (MicroProfile Config…) |

Le jeton n'est jamais écrit dans un message d'erreur ni un journal. La plateforme n'émet pas encore de jeton d'application vers service : en attendant, le jeton est fourni par la configuration de l'application (fichier renouvelé de l'extérieur, ou `forwarding`).

## Erreurs

Tout échec est une `CrystallonException` (non vérifiée) : `status()` (statut HTTP, **0 si aucune réponse**), `code()` (par exemple `CRYSTALLON-0014`), `getMessage()` (message du serveur, en anglais), `path()` (endroit du problème dans le JSON du modèle).

| Cas | `status()` | `code()` |
|---|---|---|
| 400, 404, 422… avec corps `ErrorBody` | statut reçu | code du serveur (`path()` pour les problèmes de modèle) |
| 401, 403 sans corps (jeton absent, expiré, sans le rôle `render`) | 401 / 403 | `null` |
| réponse d'un proxy (HTML, JSON illisible) | statut reçu | `null` |
| réponse 2xx qui n'est pas un PDF (`%PDF`), ou PDF trop grand | statut reçu | `null` |
| service injoignable, délai dépassé, appel interrompu | 0 | `null` |

Un payload que Jackson ne sait pas écrire lève `IllegalArgumentException` (erreur de programmation, pas d'appel réseau). Pas de nouvelle tentative automatique : le rendu n'a pas d'effet de bord, mais c'est à l'application de décider.

## JSON : Jackson 2, Jackson 3, ou le vôtre

`PayloadCodec.detect()` prend `tools.jackson.databind.json.JsonMapper` (Jackson 3) s'il est présent, sinon `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2) ; aucun des deux : `IllegalStateException` qui nomme les artefacts à ajouter. Le mapper créé écrit les dates en ISO-8601 (jamais en horodatage), les `BigDecimal` en notation décimale simple (`1234.50`, jamais `1E+3`) et ignore les propriétés inconnues des réponses.

Pour garder le mapper de l'application (modules, nommage…) :

```java
CrystallonClient.builder().baseUrl(url).json(Jackson3Codec.of(objectMapper)).build();   // ou Jackson2Codec.of(mapper)
```

ou implémenter `PayloadCodec` (deux méthodes : `byte[] write(Object)` et `<T> T read(byte[], Class<T>)`), par exemple pour un autre moteur JSON.

## Quarkus (CDI)

Le module n'apporte ni `beans.xml` ni extension : ses classes ne sont pas des beans. Une application Quarkus produit son client en quelques lignes (un module `crystallon-client-quarkus` ne se justifierait que si plusieurs applications répétaient cette classe) :

```java
@ApplicationScoped
public class CrystallonConfiguration {
    @Produces @Singleton
    CrystallonClient crystallon(ObjectMapper mapper) {            // tools.jackson.databind.ObjectMapper de Quarkus 4
        return CrystallonClient.builder()
                .baseUrl(System.getenv("GLUONIFY_SERVICE_CRYSTALLON_URL"))
                .tokens(CrystallonTokens.fromEnvironment())
                .json(Jackson3Codec.of(mapper))
                .build();
    }
}
```

Pour appeler en tant que la personne connectée : `.tokens(CrystallonTokens.forwarding(() -> jwt.getRawToken()))` (avec `JsonWebToken jwt` injecté ; hors d'une requête, le fournisseur doit renvoyer `null`).

## Image native

- Les records du SDK que Jackson lit ou écrit (`Check`, `Issue`, `ErrorBody`, `RenderRequest`, `RenderParams`) sont déclarés dans `META-INF/native-image/io.gluonify.plasma/gluonify-plasma-crystallon-client/reachability-metadata.json`, que `native-image` (donc Quarkus) lit tout seul dans le jar.
- **Les types du payload sont à la charge de l'application** : chaque record ou bean passé à `render` / `validate` doit être enregistré pour la réflexion (Quarkus : `@RegisterForReflection` sur le type, ou `Reflection.java` du composant ; ailleurs : une entrée `reachability-metadata.json` avec `allDeclaredConstructors`, `allDeclaredMethods` et `allDeclaredFields`). Un oubli ne casse qu'en natif, à la première sérialisation (`UnsupportedFeatureError: Record components not available for record class …`), sans erreur de compilation ni test JVM.
- Vérifié en natif (NIK 25, programme d'essai contre un faux serveur, Jackson 3 puis Jackson 2 avec `jsr310`) : détection du JSON, rendu, validation, erreur `ErrorBody`, dates et `BigDecimal` du payload ; sans le fichier de métadonnées ci-dessus, la première sérialisation échoue comme décrit.
- Le `HttpClient` n'est jamais dans un champ statique ni créé à la construction du client ; pas de `Random` statique ; la bibliothèque JSON absente (Jackson 2 si l'application n'a que Jackson 3, et inversement) n'est jamais chargée.

## Routes appelées

| Appel | Requête |
|---|---|
| `byId(id).render(…)` | `POST {base}/v1/templates/{id}/render?pdfa=true\|false[&version=n]`, `Accept: application/pdf` |
| `byName(nom).render(…)` | `POST {base}/v1/templates/by-name/{nom encodé}/render?…` (le nom est un segment de chemin : espace `%20`, `/` `%2F`) |
| `….validate(…)` | `POST …/render/validate[?version=n]`, `Accept: application/json` → `Check` |

Le corps est un `RenderRequest` JSON `{"data": …, "params": {…}}` (`params` : les seuls champs renseignés de `RenderOptions`). Réponse de rendu : le PDF, et les en-têtes `X-Crystallon-Pages`, `X-Crystallon-Warnings`, `X-Crystallon-PDFA` (`valid` ou `invalid`) lus dans `RenderedPdf` (0 ou `null` s'ils manquent).

## Tests

`mvn clean verify` : un faux Crystallon (`com.sun.net.httpserver.HttpServer`), aucun service externe. Les mêmes tests tournent avec cinq chemins de classes (exécutions surefire avec `classpathDependencyExcludes`, l'exclusion réelle est vérifiée) : Jackson 2 et 3 présents, Jackson 3 seul, Jackson 2 seul, Jackson 2 sans `jsr310`, aucun Jackson (erreur claire, client utilisable avec un `PayloadCodec` fourni). Le module et le SDK se compilent et se testent avec un JDK 17 (`maven.compiler.release` 17).
