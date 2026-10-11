# gluonify-plasma-fermion-client

Client WebSocket de **gluonify-fermion** (moteur de workflows BPMN et de règles DMN) pour les applications Quarkus de la plateforme. Un nœud peut soumettre des workflows (`startWorkflow`), exécuter des tâches (beans `SkillHandler`), ou les deux. Protocole : `io.gluonify.plasma.fermion.FermionMsg` (SDK).

```xml
<dependency>
  <groupId>io.gluonify.plasma</groupId>
  <artifactId>gluonify-plasma-fermion-client</artifactId>
</dependency>
```

## Configuration

| Propriété | Défaut | Rôle |
|---|---|---|
| `gluonify.fermion.client.url` | `${GLUONIFY_SERVICE_FERMION_URL:}` | URL de fermion ; vide = résolue par `GluonifyServices` (si `discover`), sinon client inactif |
| `gluonify.fermion.client.discover` | `true` | sans `url`, chercher fermion par `GluonifyServices.optional("fermion")` (surcharge, plateforme, poste du développeur après `gluonify login`), une fois ; `false` : inactif sans `url` |
| `gluonify.fermion.client.enabled` | `true` | `false` désactive le client |
| `gluonify.fermion.client.app-id` | `quarkus.application.name` | identifiant de l'application |
| `gluonify.fermion.client.node-id` | `${GLUONIFY_REPLICA:0}` | identifiant de la réplique ; le chemin est `/ws/fermion/<app-id>-<node-id>` |
| `gluonify.fermion.client.self-url` | `${GLUONIFY_SELF_URL:}` | annoncé avec `/q/openapi` dans `Hello` |
| `gluonify.fermion.client.queue-size` | `100` | `Start` gardés pendant une coupure |
| `gluonify.fermion.client.reconnect-interval` | `10s` | période du ping et de la reconnexion |

À ajouter dans l'application (gros messages fragmentés) :

```properties
quarkus.websockets-next.client.max-frame-size=4194304
quarkus.websockets-next.client.max-message-size=83886080
```

**Aucun bean à écrire pour le jeton** : sans `FermionTokenProvider` de l'application, le module produit `FermionTokenProvider.gluonify()` (`@DefaultBean`, classe `GluonifyTokenProducer`) : le jeton résolu par `GluonifyServices` à chaque connexion, c'est-à-dire le jeton d'application sur la plateforme (`"uses": ["fermion"]`) et le jeton du développeur (`gluonify login`, renouvelé) sur le poste. Un bean `FermionTokenProvider` de l'application (jeton Charm d'audience `gluonify-fermion`) le remplace : le jeton part dans la première trame `Auth`, jamais dans l'URL, puis vient le `Hello` (compétences, workflows utilisés, OpenAPI). Avec `"uses": ["fermion"]` dans l'appspec, la plateforme fournit ce jeton (sujet `app:<nom>`, rôle `gluonify:fermion:user`, renouvelé par l'agent du nœud) (fichier de `GLUONIFY_FERMION_TOKEN_FILE`, relu à chaque connexion), que le fournisseur par défaut lit déjà ; `FermionTokenProvider.platform()` en est la version stricte (variable absente : `IllegalStateException`).

## Exemple de handler

```java
@ApplicationScoped
public class StoreDocument implements SkillHandler {
    public String action() { return "DOC_STORE"; }
    public List<SkillParam> inputs() { return List.of(SkillParam.of("uuid", "STRING")); }
    public List<SkillParam> outputs() { return List.of(SkillParam.of("size", "LONG")); }
    public List<Translation> descriptions() {   // les cinq langues, ou aucune
        return SkillHandler.translations("Store a document", "Archive un document", "Archiva un documento", "Archivia un documento", "Archiviert ein Dokument");
    }
    public Map<String, Object> handle(TaskContext ctx) {
        String uuid = ctx.getString("uuid");     // SkillFailureException MISSING_PARAMETER si absent
        if (!storage.up()) throw new SkillFailureException("STORAGE_UNAVAILABLE", "storage is down");
        return Map.of("size", storage.put(uuid));
    }
}
```

`TaskContext` : `getString`, `getLong`, `getBoolean`, `getMap`, `getList(clé, type)`, `get(clé, type)`, `optional(clé, type)`, `has`. Les handlers tournent hors du thread d'événements, **en parallèle** (`InboundProcessingMode.CONCURRENT`) : une tâche longue (build natif, attente d'une décision humaine) ne retarde pas les autres commandes du nœud, et chaque `Result` part dès la fin de sa tâche (fermion les apparie par `correlationId`). Un handler doit donc être sûr en accès concurrent.
Il porte aussi `stepId()` (l’étape BPMN, la même compétence pouvant servir deux fois dans un workflow ; le `Result` la renvoie tout seul) et `organization()` (l’organisation de l’exécution, posée par fermion). Avec `correlationId`, `stepId` est une bonne clé d’idempotence : fermion renvoie la tâche en cours à un nœud qui se reconnecte. `startWorkflow(workflow, correlationId, globalTransactionId, payload, organization)` fixe l’organisation de l’exécution (nœuds de la plateforme).

## Comportement

- Reconnexion : tâche planifiée qui ping (5 s) et reconnecte ; un connecteur NEUF par tentative (un connecteur réutilisé accumule des en-têtes jusqu'au HTTP 431).
- `startWorkflow(workflow, correlationId, [globalTransactionId], payload)` : pendant une coupure, le `Start` va dans une file bornée, rejouée dans l'ordre après `Auth` et `Hello`. File pleine : `FermionQueueFullException`. Client inactif : `FermionClientException`.
- `announceUsage()` ré-annonce compétences et workflows (`WorkflowUsage`) ; `onWorkflowError((correlationId, message) -> …)` reçoit les refus ; `isConnected()`.
- Natif : les types du SDK sont dans `Reflection.java` ; ni `HttpClient` ni `Random` statiques.

Tests : `mvn clean verify` (faux serveur WebSocket dans les tests, aucun service externe).
