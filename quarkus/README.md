# gluonify-plasma-quarkus

**Default** CDI producers of Gluonify's clients for a Quarkus 4 application (Java 21): the application writes no configuration class.

```xml
<dependency>
  <groupId>io.gluonify.plasma</groupId>
  <artifactId>gluonify-plasma-quarkus</artifactId>
</dependency>
```

```java
@Inject CrystallonClient crystallon;      // nothing else: address and token resolved by GluonifyServices
```

| Bean | Producer | Source |
|---|---|---|
| `CrystallonClient` | `GluonifyProducers` (`@DefaultBean`, `@Singleton`, created on first injection) | `GluonifyServices.endpoint("crystallon")` and the application's `ObjectMapper` (Jackson 3) |
| `FermionTokenProvider` | in `gluonify-plasma-fermion-client` itself (`GluonifyTokenProducer`): this module does not depend on fermion-client, which stays optional | `FermionTokenProvider.gluonify()` |

A bean of the same type in the application replaces the default one (for example a client that forwards the signed-in person's token, see the README of crystallon-client). The client is created on first injection: an application that does not use Crystallon has nothing to declare; an application that uses it without `uses` gets, at that injection, a `GluonifyServicesException` that says what to do. Native image: nothing static, the client's `HttpClient` is created on the first call.

In tests: `GluonifyTestServices.start()` (module `gluonify-plasma-testing`) before the first injection. `mvn clean verify`: 2 tests, including a `@QuarkusTest` that injects the client and renders a PDF through the fake Crystallon with the application's mapper.

MCP tools of the application (`@McpTool`, `POST /mcp` relayed by Prism): the separate module [`gluonify-plasma-mcp`](../mcp/README.md), so that an application using these producers serves no `/mcp` and is not announced to Bup as serving MCP.
