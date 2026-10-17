# gluonify-plasma-testing

Fake Gluonify services for application tests, **explicitly enabled** (Java 17, no dependency outside `gluonify-plasma-services`):

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
    RenderedPdf pdf = CrystallonClient.create().byName("Gluonify invoice").render(payload);   // or the injected client (gluonify-plasma-quarkus)
    assertEquals("Gluonify invoice", services.crystallon().lastRender().template());
}
```

`start()` starts an in-memory **fake Crystallon** (JDK HTTP server on `127.0.0.1`, free port) and sets the override read by `GluonifyServices` (`GLUONIFY_SERVICE_CRYSTALLON_URL` and `GLUONIFY_CRYSTALLON_TOKEN` as system properties); `close()` stops it and restores the previous properties. One instance at a time per process. The fake service answers the client's routes (`/v1/templates/{id}/render`, `/v1/templates/by-name/{name}/render`, `/v1/render`, and `…/validate`) with a **valid minimal one-page PDF** (`FakeCrystallon.pdf()`) or a successful validation, requires the test token (401 otherwise), keeps each request (`renders()`, `lastRender()`: template, query, JSON body) and replays a scheduled failure (`failNext(422, "CRYSTALLON-0015", "…")`). `mvn clean verify`: 3 tests.
