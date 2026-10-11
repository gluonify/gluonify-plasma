# gluonify-plasma-quarkus

Producteurs CDI **par défaut** des clients de Gluonify pour une application Quarkus 4 (Java 21) : l'application n'écrit aucune classe de configuration.

```xml
<dependency>
  <groupId>io.gluonify.plasma</groupId>
  <artifactId>gluonify-plasma-quarkus</artifactId>
</dependency>
```

```java
@Inject CrystallonClient crystallon;      // rien d'autre : adresse et jeton résolus par GluonifyServices
```

| Bean | Producteur | Source |
|---|---|---|
| `CrystallonClient` | `GluonifyProducers` (`@DefaultBean`, `@Singleton`, créé à la première injection) | `GluonifyServices.endpoint("crystallon")` et le `ObjectMapper` (Jackson 3) de l'application |
| `FermionTokenProvider` | dans `gluonify-plasma-fermion-client` lui-même (`GluonifyTokenProducer`) : ce module ne dépend pas de fermion-client, qui reste facultatif | `FermionTokenProvider.gluonify()` |

Un bean du même type dans l'application remplace celui par défaut (par exemple un client qui transmet le jeton de la personne connectée, voir le README de crystallon-client). Le client est créé à la première injection : une application qui n'utilise pas Crystallon n'a rien à déclarer ; une application qui l'utilise sans `uses` reçoit à cette injection une `GluonifyServicesException` qui dit quoi faire. Image native : rien de statique, le `HttpClient` du client est créé au premier appel.

En test : `GluonifyTestServices.start()` (module `gluonify-plasma-testing`) avant la première injection. `mvn clean verify` : 2 tests, dont un `@QuarkusTest` qui injecte le client et rend un PDF par le faux Crystallon avec le mapper de l'application.
