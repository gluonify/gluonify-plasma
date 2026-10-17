# gluonify-plasma-fermion-client

WebSocket client for **gluonify-fermion** (BPMN workflow and DMN rule engine) for the platform's Quarkus applications. A node can submit workflows (`startWorkflow`), run tasks (`SkillHandler` beans), or both. Protocol: `io.gluonify.plasma.fermion.FermionMsg` (SDK).

```xml
<dependency>
  <groupId>io.gluonify.plasma</groupId>
  <artifactId>gluonify-plasma-fermion-client</artifactId>
</dependency>
```

## Configuration

| Property | Default | Role |
|---|---|---|
| `gluonify.fermion.client.url` | `${GLUONIFY_SERVICE_FERMION_URL:}` | fermion URL; empty = resolved by `GluonifyServices` (if `discover`), otherwise the client is inactive |
| `gluonify.fermion.client.discover` | `true` | without `url`, look for fermion through `GluonifyServices.optional("fermion")` (override, platform, developer workstation after `gluonify login`), once; `false`: inactive without `url` |
| `gluonify.fermion.client.enabled` | `true` | `false` disables the client |
| `gluonify.fermion.client.app-id` | `quarkus.application.name` | application identifier |
| `gluonify.fermion.client.node-id` | `${GLUONIFY_REPLICA:0}` | replica identifier; the path is `/ws/fermion/<app-id>-<node-id>` |
| `gluonify.fermion.client.self-url` | `${GLUONIFY_SELF_URL:}` | announced with `/q/openapi` in `Hello` |
| `gluonify.fermion.client.queue-size` | `100` | `Start` messages kept during a disconnection |
| `gluonify.fermion.client.reconnect-interval` | `10s` | ping and reconnection period |
| `gluonify.fermion.client.heartbeat-interval` | `60s` | period of the heartbeat (`progress`) sent while a handler is running (the first one is sent after one period: a short task sends none); `0s` disables it |

Add to the application (large messages are fragmented):

```properties
quarkus.websockets-next.client.max-frame-size=4194304
quarkus.websockets-next.client.max-message-size=83886080
```

**No bean to write for the token**: without an application `FermionTokenProvider`, the module produces `FermionTokenProvider.gluonify()` (`@DefaultBean`, class `GluonifyTokenProducer`): the token resolved by `GluonifyServices` on each connection, that is the application token on the platform (`"uses": ["fermion"]`) and the developer token (`gluonify login`, renewed) on the workstation. An application `FermionTokenProvider` bean (Charm token with audience `gluonify-fermion`) replaces it: the token goes in the first `Auth` frame, never in the URL, then comes the `Hello` (skills, workflows used, OpenAPI). With `"uses": ["fermion"]` in the appspec, the platform provides this token (subject `app:<name>`, role `gluonify:fermion:user`, renewed by the node agent) (file from `GLUONIFY_FERMION_TOKEN_FILE`, re-read on each connection), which the default provider already reads; `FermionTokenProvider.platform()` is its strict version (variable absent: `IllegalStateException`).

## Handler example

```java
@ApplicationScoped
public class StoreDocument implements SkillHandler {
    public String action() { return "DOC_STORE"; }
    public List<SkillParam> inputs() { return List.of(SkillParam.of("uuid", "STRING")); }
    public List<SkillParam> outputs() { return List.of(SkillParam.of("size", "LONG")); }
    public List<Translation> descriptions() {   // all five languages, or none
        return SkillHandler.translations("Store a document", "Archive un document", "Archiva un documento", "Archivia un documento", "Archiviert ein Dokument");
    }
    public Map<String, Object> handle(TaskContext ctx) {
        String uuid = ctx.getString("uuid");     // SkillFailureException MISSING_PARAMETER if absent
        if (!storage.up()) throw new SkillFailureException("STORAGE_UNAVAILABLE", "storage is down");
        return Map.of("size", storage.put(uuid));
    }
}
```

`TaskContext`: `getString`, `getLong`, `getBoolean`, `getMap`, `getList(key, type)`, `get(key, type)`, `optional(key, type)`, `has`. Handlers run off the event thread, **in parallel** (`InboundProcessingMode.CONCURRENT`): a long task (native build, waiting for a human decision) does not delay the node's other commands, and each `Result` is sent as soon as its task ends (fermion matches them by `correlationId`). A handler must therefore be safe under concurrent access.
It also carries `stepId()` (the BPMN step, since the same skill can be used twice in a workflow; the `Result` returns it by itself) and `organization()` (the organization of the execution, set by fermion). Together with `correlationId`, `stepId` is a good idempotency key: fermion resends the task in progress to a node that reconnects. `startWorkflow(workflow, correlationId, globalTransactionId, payload, organization)` sets the organization of the execution (platform nodes).

## Behavior

- Heartbeat: while a handler runs, a `Progress` (`correlationId`, `action`, `stepId`) is sent every `heartbeat-interval`; fermion does not resend a task whose node is beating. `SkillHandler.expectedDuration()` (optional, announced in `Hello` in ISO-8601) covers what the heartbeat cannot see, for example a multi-day human validation that the node keeps outside the handler.

- Reconnection: a scheduled task that pings (5 s) and reconnects; a NEW connector per attempt (a reused connector accumulates headers until HTTP 431).
- `startWorkflow(workflow, correlationId, [globalTransactionId], payload)`: during a disconnection, the `Start` goes into a bounded queue, replayed in order after `Auth` and `Hello`. Queue full: `FermionQueueFullException`. Inactive client: `FermionClientException`.
- `announceUsage()` re-announces skills and workflows (`WorkflowUsage`); `onWorkflowError((correlationId, message) -> …)` receives refusals; `isConnected()`.
- Native: the SDK types are in `Reflection.java`; no static `HttpClient` or `Random`.

Tests: `mvn clean verify` (fake WebSocket server in the tests, no external service).
