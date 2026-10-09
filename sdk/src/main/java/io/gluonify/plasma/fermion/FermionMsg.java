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

    /** Soumet un workflow. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Start(String workflow, String correlationId, String globalTransactionId, Map<String, Object> payload) implements FermionMsg {
        public Start {
            payload = payload == null ? Map.of() : payload;
        }

        public Start(String workflow, String correlationId, Map<String, Object> payload) {
            this(workflow, correlationId, null, payload);
        }
    }

    /** Ordre d'exécuter une tâche (l'action est le nom de la compétence). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Command(String correlationId, String globalTransactionId, String action, Map<String, Object> payload) implements FermionMsg {
        public Command {
            payload = payload == null ? Map.of() : payload;
        }
    }

    /** Fin d'une tâche : succès avec une charge utile, ou échec avec un message et un genre lisible par la machine ({@code errorKind}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(String correlationId, String globalTransactionId, String action, boolean success, String error, String errorKind, Map<String, Object> payload) implements FermionMsg {
        public Result {
            payload = payload == null ? Map.of() : payload;
        }

        public static Result ok(String correlationId, String action, Map<String, Object> payload) {
            return new Result(correlationId, null, action, true, null, null, payload);
        }

        public static Result failure(String correlationId, String action, String error, String errorKind) {
            return new Result(correlationId, null, action, false, error, errorKind, null);
        }
    }

    /** Refus : connexion (jeton invalide, protocole inconnu) ou workflow (absent, inactif). {@code correlationId} est vide pour un refus de connexion. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(String correlationId, String message) implements FermionMsg {}
}
