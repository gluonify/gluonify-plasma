# gluonify-plasma-mcp

**MCP tools of a Quarkus 4 application** (Java 21): the application declares business tools (diagnostics, internal state, statistics, business settings) with `@McpTool`; Prism, the customers' MCP, discovers them (`tools/list` at deployment) and relays the calls of the customers' AI assistants as `app.<application>.<tool>`. The application manages no AI token, rate limit or audit: Prism does. Specification: [gluonify-codex/app-mcp.md](https://github.com/gluonify/gluonify-codex/blob/main/app-mcp.md); the Python counterpart is `gluonify.fastapi.mcp` (gluonify-plasma-python), with the **same wire behaviour**.

| Supported | Not yet |
|---|---|
| `@McpTool` on the methods of an `McpToolbox` bean, input schema derived from an arguments record or given explicitly, `McpCaller` parameter | Prism's relay, discovery and proposals (app-mcp.md phases 3 and 4) |
| `POST /mcp`: JSON-RPC 2.0 `initialize`, `tools/list`, `tools/call`, notifications (202) | batches, streaming, resources, prompts, sampling (out of scope, § 6) |
| identity: the application's Charm token verification (`quarkus-oidc`), audience check, Prism's service token only, write role `gluonify:mcp:write` | Higgs: `mcp: true` from the marker, `/mcp` blocked at the public ingress in PRD (phase 2) |
| limits checked at startup and per call (name, 50 tools, 16 KiB schema, 64 KiB arguments, 256 KiB response, 68 KiB body) | verified in native and end to end (`e2e-app-mcp.sh`, phase 1 examples) |

```xml
<dependency>
  <groupId>io.gluonify.plasma</groupId>
  <artifactId>gluonify-plasma-mcp</artifactId>
</dependency>
```

```java
@ApplicationScoped
@RegisterForReflection(targets = {ShopTools.StatsInput.class, ShopTools.Stats.class}) // native image: the tools and their records are read by reflection
public class ShopTools implements McpToolbox {
    public record StatsInput(@McpParam("Number of days, 1 to 30") int days, Optional<String> category) {}
    public record Stats(int days, long orders) {}

    @McpTool(description = "Number of orders per day over the last N days.")          // name: order_stats
    public Stats orderStats(StatsInput in) { return new Stats(in.days(), count(in.days())); }

    @McpTool(name = "reset_cache", description = "Empties the product cache.", write = true)
    public String resetCache(McpCaller caller) { cache.clear(); return "done"; }
}
```

**Activation.** Adding the dependency is the activation: its jar is a bean archive, so `POST /mcp` is served, and it carries the marker that tells Bup the application serves MCP. Nothing is added to an application that does not depend on it (the SDK `gluonify-plasma-sdk`, which platform components index for `/q/usage` and which Prism and Gauge use beside their own `/mcp`, contains only the constants `io.gluonify.plasma.mcp.McpContract`).

## Declaring tools

| Element | Rule |
|---|---|
| `@McpTool(name, description, write = false, inputSchema = "")` | on a **public** method of a CDI bean implementing **`McpToolbox`** (the marker interface also keeps the bean from being removed by ArC). `name`: `[a-z][a-z0-9_]{0,40}`, default the method name in snake case (`orderStats` -> `order_stats`); `description` required (what the AI reads); `write`: the tool changes something, it then needs `gluonify:mcp:write` and Prism turns the call into a proposal to approve |
| arguments | at most **one** arguments parameter: a **record** (input schema derived, below), a `Map<String, ?>` or a Jackson `JsonNode` (then give `inputSchema`, a JSON Schema of type `object`); no parameter = no argument (`{"type":"object","properties":{}}`). Read strictly with the application's `ObjectMapper`: unknown property, wrong type, `null` for a primitive or a missing required component is a `-32602` |
| `McpCaller` | optional parameter: the verified caller (`subject`, `roles`, the `JsonWebToken`) |
| `@McpParam("...")` | on a record component: its `description` in the schema |
| result | a `String` (as is), or anything the application's `ObjectMapper` serialises; an object (record, map) is also returned as `structuredContent`. An exception becomes `isError: true` with the text `the tool failed` (detail in the `gluonify.mcp` log, never to the AI); a value that cannot be serialised or a response over 256 KiB is an `isError` too |
| checked at startup | grammar, unique names, at most **50** tools, schema of type `object` of at most **16 KiB**, signature, public method: otherwise `IllegalStateException` and the application does not start. A toolbox in which no tool can be read is logged (typically a native image without `@RegisterForReflection`) |

**Derived schema** (`McpSchemas`): a record is `{"type":"object","properties":…,"required":[…],"additionalProperties":false}`, every component required except `Optional`, `OptionalInt`, `OptionalLong`, `OptionalDouble`; `String`/`char` -> `string`, enum -> `string` + `enum`, `boolean`, integers and `BigInteger` -> `integer`, `float`/`double`/`BigDecimal` -> `number`, arrays and collections -> `array` + `items` (`Set`: `uniqueItems`), `Map<String, V>` -> `object` + `additionalProperties`, nested records recursively (a recursive record is cut at its second occurrence), anything else (`Object`, `JsonNode`) -> `{}`. For anything richer (bounds, patterns), give `inputSchema`.

**Native image.** No class is generated at run time and the JSON-RPC envelope is built with Jackson trees (nothing to register for it). The application's tools are read and invoked by reflection: annotate the toolbox with `@RegisterForReflection` and register its arguments and result records (on themselves or through `targets`), as in the example.

## Wire behaviour (identical in Python)

JSON-RPC 2.0 over `POST /mcp`, one message per request, answered `application/json`.

| Message | Answer |
|---|---|
| `initialize` | `protocolVersion`: the client's if it is `2025-06-18`, `2025-03-26` or `2024-11-05`, else `2025-06-18`; `capabilities: {"tools":{"listChanged":false}}`; `serverInfo: {"name":"gluonify-mcp","version":<quarkus.application.version>}` |
| `tools/list` | `tools`: per tool `{name, description, inputSchema, annotations:{readOnlyHint: !write}, write}`, sorted by name |
| `tools/call` | `{content:[{type:"text",text}], structuredContent? (object results only), isError:false}`; failures as `isError:true` (above). **No `untrusted` marker** is added here: Prism marks every relayed response itself (app-mcp.md § 2, § 8 d6), a marker set by the application could not be trusted anyway |
| JSON-RPC errors (HTTP 200, `error`) | `-32601` unknown method; `-32602` missing or non-string `name`, non-object `arguments`, unknown tool, invalid arguments, arguments over 64 KiB; `-32700` unparseable JSON; `-32600` invalid request (not an object, no `jsonrpc: "2.0"`, no string `method`, `id` neither string nor number, a batch) |
| a message without `id` | notification: HTTP **202**, empty body (never runs a tool) |
| a body over 68 KiB | HTTP **413** `{"error":"request too large"}` |
| `GET /mcp` | 405 |

## Caller identity

Decision 3 of app-mcp.md: Prism calls with the existing **application-to-service Charm token**, no new header. Verification is the application's own Charm configuration, as for its other endpoints (`quarkus-oidc`: `quarkus.oidc.auth-server-url` = Charm's realm, signature against its JWKS, issuer, expiry, `quarkus.oidc.roles.role-claim-path=roles`; see gluonify-source), then `/mcp` requires:

- a **JWT principal** (an anonymous identity or another kind of principal: **401** `{"error":"a valid application token is required"}` with `WWW-Authenticate: Bearer`);
- an `aud` claim containing the application's audience: `gluonify.mcp.audience`, else `quarkus.oidc.token.audience`, else `gluonify-<quarkus.application.name>` (401 otherwise, even if the OIDC configuration accepts any audience);
- the token of the **platform's relay**: claims `gluonify_kind` = `service` and `gluonify_app` = `gluonify.mcp.relay-app` (`prism`), those Higgs has Charm put in every application-to-service token (`CharmClient.serviceRequest`, subject `app:prism`). Anything else (a customer's user or API token of the same audience, another application's service token, a token without `gluonify_kind=service`): **403** `{"error":"only the platform's relay (Prism) may call /mcp"}` (app-mcp.md § 3: the application refuses any call without Prism's identity);
- for a **`write`** tool, the role **`gluonify:mcp:write`** in the `roles` claim (or among the identity's roles): **403** `{"error":"missing role: gluonify:mcp:write"}` otherwise, before the arguments are read and before the tool runs. Prism puts it only in the token of a call it relays for an **approved** write proposal (decision 4); under the reserved `gluonify:` prefix, a customer's own user or API token can never carry it.

Prism's service token may call `initialize`, `tools/list` and the read tools. The resource is `@PermitAll` and decides 401/403 itself (it also works under `quarkus.security.jaxrs.deny-unannotated-endpoints=true`). `/mcp` is meant for the **internal listener** only; Higgs blocks it at the public ingress in PRD (`ManagementPaths`), nothing to configure here.

## The marker Bup reads

The jar carries **`META-INF/gluonify/mcp`** (`McpContract.MARKER`; content `mcp`, not read). Bup reads it with the same rules as the service markers (jars of the trusted `io.gluonify.plasma` group on the **runtime** classpath) and sets `mcp: true` (app-mcp.md § 3, decision 2). It is a **capability, not a service**: it is outside `META-INF/gluonify/services/` and must never become a `uses` entry (Python: the separate entry-point group `gluonify.mcp`). Because the marker is in a dedicated module, it means that the application depends on the MCP server, which serves `/mcp`; Prism's discovery (`tools/list`) remains the confirmation (an application without tools lists none).

## Configuration

| Property | Default | Role |
|---|---|---|
| `gluonify.mcp.audience` | `quarkus.oidc.token.audience`, else `gluonify-<quarkus.application.name>` | audience required in the `aud` claim of the caller's token |
| `gluonify.mcp.relay-app` | `prism` | the only application (`gluonify_app` claim of a `gluonify_kind=service` token) allowed to call `/mcp`; change it for tests or development only |
| `quarkus.application.version` | `unknown` | `serverInfo.version` of `initialize` |

The limits are constants of `io.gluonify.plasma.mcp.McpContract` (SDK, Java 17, shared with Prism): `TOOL_NAME`, `MAX_TOOLS` 50, `MAX_SCHEMA_BYTES` 16 KiB, `MAX_ARGUMENTS_BYTES` 64 KiB, `MAX_RESPONSE_BYTES` 256 KiB, `MAX_BODY_BYTES` 68 KiB, `WRITE_ROLE`, `PROTOCOL_VERSIONS`, `MARKER`.

## Tests

`mvn clean verify`: 26 tests. `McpServerTest` (9: `initialize`, `tools/list` shape, read call, write role, `isError` results, `-32602`, `-32601` / `-32700` / `-32600`, notifications, 413), `McpRegistryTest` (4: name grammar, declaration errors, 50 tools, toolbox without tool), `McpSchemasTest` (2), `McpResourceTest` (10, `@QuarkusTest` with `@TestSecurity` / `@JwtSecurity`: 401 anonymous, non-JWT or other audience; 403 for a user token of the right audience, another application's service token, Prism without `gluonify_kind=service`; Prism's service token lists and calls read tools; 403 then 200 for the write role; 202, 413, 405), `McpMarkerTest` (1).
