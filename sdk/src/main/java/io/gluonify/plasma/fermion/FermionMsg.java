package io.gluonify.plasma.fermion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * Protocol between an application node and gluonify-fermion (BPMN workflow and DMN rules engine), over WebSocket (text, JSON, field "t").
 * The node opens the connection then authenticates in its first message — never in the URL:
 * <ul>
 *   <li>node -> fermion: {@link Auth} (Charm token), then {@link Hello} (skills and workflows used), {@link Start} (submit a workflow),
 *       {@link Result} (a task is finished), {@link Progress} (a long task is still running);</li>
 *   <li>fermion -> node: {@link Command} (run a task), {@link Error} (workflow refused or connection refused).</li>
 * </ul>
 * {@code globalTransactionId} anchors an entire business transaction across a fan-out (a submission that becomes N documents, each with its own
 * {@code correlationId}): set at {@link Start} (by default the first {@code correlationId}) and inherited as is by all the branches.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FermionMsg.Auth.class, name = "auth"),
        @JsonSubTypes.Type(value = FermionMsg.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = FermionMsg.Start.class, name = "start"),
        @JsonSubTypes.Type(value = FermionMsg.Command.class, name = "command"),
        @JsonSubTypes.Type(value = FermionMsg.Result.class, name = "result"),
        @JsonSubTypes.Type(value = FermionMsg.Progress.class, name = "progress"),
        @JsonSubTypes.Type(value = FermionMsg.Error.class, name = "error")
})
public sealed interface FermionMsg {
    /** Protocol version, announced in {@link Auth}. */
    int PROTOCOL = 1;

    /** First message from the node: Charm token (audience {@code gluonify-fermion}) and protocol version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Auth(String token, int protocol) implements FermionMsg {
        public Auth(String token) {
            this(token, PROTOCOL);
        }
    }

    /** Announcement from the node, on connection then on every change: skills, workflows used, address of its OpenAPI. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hello(String appId, String nodeId, List<SkillDescriptor> skills, List<WorkflowDescriptor> usedWorkflows, String openApiUrl) implements FermionMsg {
        public Hello {
            skills = skills == null ? List.of() : skills;
            usedWorkflows = usedWorkflows == null ? List.of() : usedWorkflows;
        }
    }

    /**
     * Submits a workflow. {@code organization} (optional) is the organization of the execution, set by the trusted node that submits
     * (Prism, Bup): fermion passes it to every task in place of any {@code organization} value of the payload.
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
     * Order to run a task: {@code action} is the skill name, {@code stepId} the identifier of the step in the workflow (id of the
     * BPMN node; optional). The same skill may appear several times in a workflow ({@code BUP_DEPLOY} in SBX then in QUA): the node
     * returns {@code stepId} as is in its {@link Result}, and may use it with {@code correlationId} as an idempotency key (fermion
     * resends the task in progress to a node that reconnects).
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
     * End of a task: success with a payload, or failure with a message and a machine-readable kind ({@code errorKind}).
     * {@code stepId} repeats that of the {@link Command} (optional: without it, fermion finds the step it entrusted to the node).
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

        /** Success in response to {@code command}: repeats its transaction, its action and its {@code stepId}. */
        public static Result ok(Command command, Map<String, Object> payload) {
            return new Result(command.correlationId(), command.globalTransactionId(), command.action(), true, null, null, payload, command.stepId());
        }

        /** Failure in response to {@code command}: repeats its transaction, its action and its {@code stepId}. */
        public static Result failure(Command command, String error, String errorKind) {
            return new Result(command.correlationId(), command.globalTransactionId(), command.action(), false, error, errorKind, null, command.stepId());
        }
    }

    /**
     * Heartbeat of a task in progress (node -> fermion): the node is still working on the {@link Command} {@code correlationId} + {@code stepId}.
     * Fermion then pushes back the deadline beyond which it deems the node silent and resends the task; a node that never sends one remains
     * compatible (the deadline comes from the duration announced by the skill, {@link SkillDescriptor#expectedDuration()}, or from fermion's setting).
     * {@code message} is an optional free text (readable progress). An earlier fermion ignores the frame (read error logged).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Progress(String correlationId, String globalTransactionId, String action, String stepId, String message) implements FermionMsg {
        /** Heartbeat in response to {@code command}: repeats its transaction, its action and its {@code stepId}. */
        public static Progress of(Command command, String message) {
            return new Progress(command.correlationId(), command.globalTransactionId(), command.action(), command.stepId(), message);
        }
    }

    /** Refusal: connection (invalid token, unknown protocol) or workflow (missing, inactive). {@code correlationId} is empty for a connection refusal. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(String correlationId, String message) implements FermionMsg {}
}
