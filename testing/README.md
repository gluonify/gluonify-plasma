# gluonify-plasma-testing

Faux services de Gluonify pour les tests des applications, **activés explicitement** (Java 17, aucune dépendance hors de `gluonify-plasma-services`) :

```xml
<dependency>
  <groupId>io.gluonify.plasma</groupId>
  <artifactId>gluonify-plasma-testing</artifactId>
  <scope>test</scope>
</dependency>
```

```java
static GluonifyTestServices services;
@BeforeAll static void start() { services = GluonifyTestServices.start(); }
@AfterAll  static void stop()  { services.close(); }

@Test void invoice() {
    RenderedPdf pdf = CrystallonClient.create().byName("Gluonify invoice").render(payload);   // ou le client injecté (gluonify-plasma-quarkus)
    assertEquals("Gluonify invoice", services.crystallon().lastRender().template());
}
```

`start()` démarre un **faux Crystallon** en mémoire (serveur HTTP du JDK sur `127.0.0.1`, port libre) et pose la surcharge que lit `GluonifyServices` (`GLUONIFY_SERVICE_CRYSTALLON_URL` et `GLUONIFY_CRYSTALLON_TOKEN` en propriétés système) ; `close()` l'arrête et rend les propriétés d'avant. Une instance à la fois par processus. Le faux service répond aux routes du client (`/v1/templates/{id}/render`, `/v1/templates/by-name/{nom}/render`, `/v1/render`, et `…/validate`) par un **PDF minimal valide** d'une page (`FakeCrystallon.pdf()`) ou une validation réussie, exige le jeton de test (401 sinon), garde chaque demande (`renders()`, `lastRender()` : modèle, requête, corps JSON) et rejoue un échec programmé (`failNext(422, "CRYSTALLON-0015", "…")`). `mvn clean verify` : 3 tests.
