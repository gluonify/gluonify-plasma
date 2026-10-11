package io.gluonify.plasma.fermion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * Protocole entre un nœud applicatif et gluonify-fermion (moteur de workflows BPMN et de règles DMN), sur WebSocket (texte, JSON, champ « t »).
 * Le nœud ouvre la connexion puis s'authentifie dans son premier message — jamais dans l'URL :
 * <ul>
 *   <li>nœud -> fermion : {@link Auth} (jeton Charm), puis {@link Hello} (compétences et workflows utilisés), {@link Start} (soumettre un workflow),
 *       {@link Result} (une tâche est terminée) ;</li>
 *   <li>fermion -> nœud : {@link Command} (exécuter une tâche), {@link Error} (workflow refusé ou connexion refusée).</li>
 * </ul>
 * {@code globalTransactionId} ancre une transaction métier entière à travers un éclatement (un dépôt qui devient N documents, chacun avec son
 * {@code correlationId}) : fixé au {@link Start} (par défaut le premier {@code correlationId}) et hérité tel quel par toutes les branches.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FermionMsg.Auth.class, name = "auth"),
        @JsonSubTypes.Type(value = FermionMsg.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = FermionMsg.Start.class, name = "start"),
        @JsonSubTypes.Type(value = FermionMsg.Command.class, name = "command"),
        @JsonSubTypes.Type(value = FermionMsg.Result.class, name = "result"),
        @JsonSubTypes.Type(value = FermionMsg.Error.class, name = "error")
})
public sealed interface FermionMsg {
    /** Version du protocole, annoncée dans {@link Auth}. */
    int PROTOCOL = 1;

    /** Premier message du nœud : jeton Charm (audience {@code gluonify-fermion}) et version du protocole. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Auth(String token, int protocol) implements FermionMsg {
        public Auth(String token) {
            this(token, PROTOCOL);
        }
    }

    /** Annonce du nœud, à la connexion puis à chaque changement : compétences, workflows utilisés, adresse de son OpenAPI. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hello(String appId, String nodeId, List<SkillDescriptor> skills, List<WorkflowDescriptor> usedWorkflows, String openApiUrl) implements FermionMsg {
        public Hello {
            skills = skills == null ? List.of() : skills;
            usedWorkflows = usedWorkflows == null ? List.of() : usedWorkflows;
        }
    }

    /**
     * Soumet un workflow. {@code organization} (facultative) est l'organisation de l'exécution, posée par le nœud de confiance qui soumet
     * (Prism, Bup) : fermion la transmet à chaque tâche à la place de toute valeur {@code organization} de la charge utile.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Start(String workflow, String correlationId, String globalTransactionId, Map<String, Object> payload, String organization) implements FermionMsg {
        public Start {
            payload = payload == null ? Map.of() : payload;
        }

        public Start(String workflow, String correlationId, String globalTransactionId, Map<String, Object> payload) {
            this(workflow, correlationId, globalTransactionId, payload, null);
        }

        public Start(String workflow, String correlationId, Map<String, Object> payload) {
            this(workflow, correlationId, null, payload, null);
        }
    }

    /**
     * Ordre d'exécuter une tâche : {@code action} est le nom de la compétence, {@code stepId} l'identifiant de l'étape dans le workflow (id du
     * nœud BPMN ; facultatif). Une même compétence peut figurer plusieurs fois dans un workflow ({@code BUP_DEPLOY} en SBX puis en QUA) : le nœud
     * renvoie {@code stepId} tel quel dans son {@link Result}, et peut s'en servir avec {@code correlationId} comme clé d'idempotence (fermion
     * renvoie la tâche en cours à un nœud qui se reconnecte).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Command(String correlationId, String globalTransactionId, String action, Map<String, Object> payload, String stepId) implements FermionMsg {
        public Command {
            payload = payload == null ? Map.of() : payload;
        }

        public Command(String correlationId, String globalTransactionId, String action, Map<String, Object> payload) {
            this(correlationId, globalTransactionId, action, payload, null);
        }
    }

    /**
     * Fin d'une tâche : succès avec une charge utile, ou échec avec un message et un genre lisible par la machine ({@code errorKind}).
     * {@code stepId} reprend celui du {@link Command} (facultatif : sans lui, fermion retrouve l'étape qu'il a confiée au nœud).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(String correlationId, String globalTransactionId, String action, boolean success, String error, String errorKind, Map<String, Object> payload,
                  String stepId) implements FermionMsg {
        public Result {
            payload = payload == null ? Map.of() : payload;
        }

        public Result(String correlationId, String globalTransactionId, String action, boolean success, String error, String errorKind, Map<String, Object> payload) {
            this(correlationId, globalTransactionId, action, success, error, errorKind, payload, null);
        }

        public static Result ok(String correlationId, String action, Map<String, Object> payload) {
            return new Result(correlationId, null, action, true, null, null, payload, null);
        }

        public static Result failure(String correlationId, String action, String error, String errorKind) {
            return new Result(correlationId, null, action, false, error, errorKind, null, null);
        }

        /** Succès en réponse à {@code command} : reprend sa transaction, son action et son {@code stepId}. */
        public static Result ok(Command command, Map<String, Object> payload) {
            return new Result(command.correlationId(), command.globalTransactionId(), command.action(), true, null, null, payload, command.stepId());
        }

        /** Échec en réponse à {@code command} : reprend sa transaction, son action et son {@code stepId}. */
        public static Result failure(Command command, String error, String errorKind) {
            return new Result(command.correlationId(), command.globalTransactionId(), command.action(), false, error, errorKind, null, command.stepId());
        }
    }

    /** Refus : connexion (jeton invalide, protocole inconnu) ou workflow (absent, inactif). {@code correlationId} est vide pour un refus de connexion. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(String correlationId, String message) implements FermionMsg {}
}
