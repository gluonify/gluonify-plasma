# gluonify-plasma-gdown

gDown over Bolt for customer applications, in three small pieces:

- **`GdownDriver.create()`**: a Neo4j `Driver` towards the platform's gDown **without configuration**. The Bolt address is the one the platform injects (`GLUONIFY_GDOWN_BOLT_URL`, else port 7687 of the service address `GLUONIFY_SERVICE_GDOWN_URL`), the application's token is presented as a bearer token and read again every five minutes (the node agent renews it), and the platform's internal root (`GLUONIFY_CA_FILE`) is added to the certificates the driver trusts. Outside the platform the resolution of `gluonify-plasma-services` applies: `GLUONIFY_SERVICE_GDOWN_URL` and `GLUONIFY_GDOWN_TOKEN[_FILE]` as an override, or the developer's zone after `gluonify login`. TLS is mandatory: a plain `bolt://` address is accepted only towards the local machine or a private network; a certificate is never trusted blindly (`+ssc` is read as `+s`).
- **`WideNumbers`**: gDown's integers beyond 64 bits (`INT128`, `UINT64`…) and `FLOAT128`/`FLOAT256` values travel over Bolt as their decimal text. `WideNumbers.integer(BigInteger)` and `decimal(BigDecimal)` make a parameter, `WideNumbers.cast("p", "INT128")` the `CAST($p AS INT128)` that gives it its type in the statement, `integer(Value)` and `decimal(Value)` read a result back whatever its Bolt form.
- **Converters**: `io.gluonify.plasma.gdown.ogm.BigIntegerConverter` / `BigDecimalConverter` for Neo4j-OGM (`@Convert`), `io.gluonify.plasma.gdown.springdata.BigIntegerPropertyConverter` / `BigDecimalPropertyConverter` for Spring Data Neo4j (`@ConvertWith`). A value that fits a Bolt integer is stored as one; a wider one is stored as its text (a statement with `CAST` stores the typed value).

The jar carries the service marker `META-INF/gluonify/services/gdown`: Bup adds `?gdown` to `uses` when it finds it on the runtime classpath (it already infers `gdown` from the Neo4j driver, Neo4j-OGM, Spring Data Neo4j and `quarkus-neo4j`).

## Dependencies

Java 17. Two mandatory dependencies, transitive and dependency-free: `gluonify-plasma-sdk` and `gluonify-plasma-services`. The client libraries are **never transitive**: the application brings its own Neo4j Java driver (5.x or 6.x; compiled against 5.28), and, if it uses them, Neo4j-OGM (compiled against 5.0) or Spring Data Neo4j (compiled against 7.5). The converter classes load only when their framework is on the classpath.

```xml
<dependency>
  <groupId>io.gluonify.plasma</groupId>
  <artifactId>gluonify-plasma-gdown</artifactId>
  <version>${gluonify.plasma.version}</version>
</dependency>
<dependency>
  <groupId>org.neo4j.driver</groupId>
  <artifactId>neo4j-java-driver</artifactId>
  <version>5.28.15</version>
</dependency>
```

## Example

```java
try (Driver driver = GdownDriver.create(); Session session = driver.session()) {
    BigInteger amount = new BigInteger("170141183460469231731687303715884105727");
    session.run("CREATE (:Payment {amount: " + WideNumbers.cast("amount", "INT128") + "})", Map.of("amount", WideNumbers.integer(amount)));
    Value v = session.run("MATCH (p:Payment) RETURN p.amount").single().get(0);
    BigInteger back = WideNumbers.integer(v); // the text gDown sends for a wide integer, or a Bolt integer
}
```

A statement in the GQL language mode may also write the literal: `CYPHER ql=GQL CREATE (:Payment {amount: 170141183460469231731687303715884105727})`.

## Tests

`WideNumbersTest`, `GdownDriverTest` (settings, schemes, trust, token manager; the driver is built without connecting), `BigIntegerConverterTest`, `BigDecimalConverterTest`, `PropertyConverterTest`, `ServiceMarkerTest`.
