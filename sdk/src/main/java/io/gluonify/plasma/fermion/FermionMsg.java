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
 *       {@link Result} (a task is finished), {@link Progress} (a long task is still running, optionally with structured {@code data});</li>
 *   <li>fermion -> node: {@link Command} (run a task), {@link Cancel} (stop the task in progress), {@link Done} (a workflow this node started
 *       ended successfully), {@link Error} (workflow refused or abandoned, or connection refused).</li>
 * </ul>
 * {@code globalTransactionId} anchors an entire business transaction across a fan-out (a submission that becomes N documents, each with its own
 * {@code correlationId}): set at {@link Start} (by default the first {@code correlationId}) and inherited as is by all the branches.
 * <p>
 * {@link Done}, {@link Cancel}, {@link Progress#data()}, {@link Hello#capacity()} and {@link SkillDescriptor#requires()} were added without
 * changing {@link #PROTOCOL}: every record ignores unknown fields, and a node that does not know a frame type logs a read error and goes on.
 *
 * @author David CABILLIC
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FermionMsg.Auth.class, name = "auth"),
        @JsonSubTypes.Type(value = FermionMsg.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = FermionMsg.Start.class, name = "start"),
        @JsonSubTypes.Type(value = FermionMsg.Command.class, name = "command"),
        @JsonSubTypes.Type(value = FermionMsg.Result.class, name = "result"),
        @JsonSubTypes.Type(value = FermionMsg.Progress.class, name = "progress"),
        @JsonSubTypes.Type(value = FermionMsg.Done.class, name = "done"),
        @JsonSubTypes.Type(value = FermionMsg.Cancel.class, name = "cancel"),
        @JsonSubTypes.Type(value = FermionMsg.Error.class, name = "error")
})
public sealed interface FermionMsg {
    /** Protocol version, announced in {@link Auth}. */
    int PROTOCOL = 1;

    /** First message from the node: Charm token (audience {@code gluonify-fermion}) and protocol version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Auth(String token, int protocol) implements FermionMsg {
        public Auth(final String token) {
            this(token, PROTOCOL);
        }
    }

    /**
     * Announcement from the node, on connection then on every change: skills, workflows used, address of its OpenAPI and, optionally, its
     * {@link NodeCapacity} (slots and closed windows, extension E4; {@code null} or empty: placement by capability and round robin only).
     *
     * @param appId         application of the node
     * @param nodeId        node within the application
     * @param skills        skills the node runs
     * @param usedWorkflows workflows the node submits
     * @param openApiUrl    address of the node's OpenAPI, or {@code null}
     * @param capacity      slots and closed windows, or {@code null}
     * @author David CABILLIC
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hello(String appId, String nodeId, List<SkillDescriptor> skills, List<WorkflowDescriptor> usedWorkflows, String openApiUrl,
                 NodeCapacity capacity) implements FermionMsg {

        /**
         * Normalises the lists ({@code null} becomes empty) and an empty capacity to {@code null}.
         *
         * @param appId         application of the node
         * @param nodeId        node within the application
         * @param skills        skills the node runs
         * @param usedWorkflows workflows the node submits
         * @param openApiUrl    address of the node's OpenAPI, or {@code null}
         * @param capacity      slots and closed windows, or {@code null}
         */
        public Hello {

            skills = skills == null ? List.of() : skills;
            usedWorkflows = usedWorkflows == null ? List.of() : usedWorkflows;
            capacity = capacity == null || capacity.isEmpty() ? null : capacity;
        }

        /**
         * Announcement without capacity (the form of the nodes before E4).
         *
         * @param appId         application of the node
         * @param nodeId        node within the application
         * @param skills        skills the node runs
         * @param usedWorkflows workflows the node submits
         * @param openApiUrl    address of the node's OpenAPI, or {@code null}
         */
        public Hello(final String appId, final String nodeId, final List<SkillDescriptor> skills, final List<WorkflowDescriptor> usedWorkflows,
                     final String openApiUrl) {
            this(appId, nodeId, skills, usedWorkflows, openApiUrl, null);
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

        public Start(final String workflow, final String correlationId, final String globalTransactionId, final Map<String, Object> payload) {
            this(workflow, correlationId, globalTransactionId, payload, null);
        }

        public Start(final String workflow, final String correlationId, final Map<String, Object> payload) {
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

        public Command(final String correlationId, final String globalTransactionId, final String action, final Map<String, Object> payload) {
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

        public Result(final String correlationId, final String globalTransactionId, final String action, final boolean success, final String error, final String errorKind, final Map<String, Object> payload) {
            this(correlationId, globalTransactionId, action, success, error, errorKind, payload, null);
        }

        public static Result ok(final String correlationId, final String action, final Map<String, Object> payload) {
            return new Result(correlationId, null, action, true, null, null, payload, null);
        }

        public static Result failure(final String correlationId, final String action, final String error, final String errorKind) {
            return new Result(correlationId, null, action, false, error, errorKind, null, null);
        }

        /** Success in response to {@code command}: repeats its transaction, its action and its {@code stepId}. */
        public static Result ok(final Command command, final Map<String, Object> payload) {
            return new Result(command.correlationId(), command.globalTransactionId(), command.action(), true, null, null, payload, command.stepId());
        }

        /** Failure in response to {@code command}: repeats its transaction, its action and its {@code stepId}. */
        public static Result failure(final Command command, final String error, final String errorKind) {
            return new Result(command.correlationId(), command.globalTransactionId(), command.action(), false, error, errorKind, null, command.stepId());
        }
    }

    /**
     * Heartbeat of a task in progress (node -> fermion): the node is still working on the {@link Command} {@code correlationId} + {@code stepId}.
     * Fermion then pushes back the deadline beyond which it deems the node silent and resends the task; a node that never sends one remains
     * compatible (the deadline comes from the duration announced by the skill, {@link SkillDescriptor#expectedDuration()}, or from fermion's setting).
     * {@code message} is an optional free text (readable progress); {@code data} (optional, extension E2) is a structured event (phase, tool call,
     * cost so far) that fermion keeps in the execution's timeline, bounded in size and number. An earlier fermion ignores the frame (read error
     * logged); an earlier fermion that knows the frame ignores {@code data}.
     *
     * @param correlationId       instance of the task
     * @param globalTransactionId business transaction, or {@code null}
     * @param action              skill of the task
     * @param stepId              step of the task, or {@code null}
     * @param message             free text, or {@code null}
     * @param data                structured event, or {@code null}
     * @author David CABILLIC
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Progress(String correlationId, String globalTransactionId, String action, String stepId, String message,
                    Map<String, Object> data) implements FermionMsg {

        /**
         * Normalises an empty {@code data} to {@code null}: a plain heartbeat carries none.
         *
         * @param correlationId       instance of the task
         * @param globalTransactionId business transaction, or {@code null}
         * @param action              skill of the task
         * @param stepId              step of the task, or {@code null}
         * @param message             free text, or {@code null}
         * @param data                structured event, or {@code null}
         */
        public Progress {

            data = data == null || data.isEmpty() ? null : data;
        }

        /**
         * Heartbeat without structured data (the form before E2).
         *
         * @param correlationId       instance of the task
         * @param globalTransactionId business transaction, or {@code null}
         * @param action              skill of the task
         * @param stepId              step of the task, or {@code null}
         * @param message             free text, or {@code null}
         */
        public Progress(final String correlationId, final String globalTransactionId, final String action, final String stepId, final String message) {
            this(correlationId, globalTransactionId, action, stepId, message, null);
        }

        /**
         * Heartbeat in response to {@code command}: repeats its transaction, its action and its {@code stepId}.
         *
         * @param command the task in progress
         * @param message free text, or {@code null}
         * @return the heartbeat
         */
        public static Progress of(final Command command, final String message) {
            return of(command, message, null);
        }

        /**
         * Structured progress event in response to {@code command}: repeats its transaction, its action and its {@code stepId}.
         *
         * @param command the task in progress
         * @param message free text, or {@code null}
         * @param data    structured event, or {@code null}
         * @return the progress frame
         */
        public static Progress of(final Command command, final String message, final Map<String, Object> data) {
            return new Progress(command.correlationId(), command.globalTransactionId(), command.action(), command.stepId(), message, data);
        }
    }

    /**
     * Successful end of a workflow (fermion -> the node that submitted it with {@link Start}, when it is online; extension E1): the symmetric of
     * the {@link Error} sent on an abandon. {@code payload} is the final state of the instance.
     *
     * @param correlationId       the instance, as given in {@link Start}
     * @param globalTransactionId business transaction, or {@code null}
     * @param workflow            the workflow that ended
     * @param payload             final variables of the instance (never {@code null})
     * @author David CABILLIC
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Done(String correlationId, String globalTransactionId, String workflow, Map<String, Object> payload) implements FermionMsg {

        /**
         * Normalises a {@code null} payload to empty.
         *
         * @param correlationId       the instance
         * @param globalTransactionId business transaction, or {@code null}
         * @param workflow            the workflow that ended
         * @param payload             final variables of the instance
         */
        public Done {

            payload = payload == null ? Map.of() : payload;
        }
    }

    /**
     * Order to stop the task in progress (fermion -> the node the task is assigned to; extension E3): sent when the execution is deleted,
     * abandoned or interrupted. The node stops as soon as it can and answers with a {@link Result} failure of kind {@link #INTERRUPTED}, which
     * fermion takes as the step's terminal state without resending it. {@code resumable} says that the work may be resumed later (a kill
     * switch), so the node keeps its checkpoint.
     *
     * @param correlationId the instance of the task
     * @param stepId        the step of the task, or {@code null}
     * @param action        the skill of the task
     * @param reason        why the task is stopped (readable)
     * @param resumable     whether the node should keep what it needs to resume
     * @author David CABILLIC
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cancel(String correlationId, String stepId, String action, String reason, boolean resumable) implements FermionMsg {

        /** {@code errorKind} of the {@link Result} that acknowledges a cancelled task. */
        public static final String INTERRUPTED = "interrupted";
    }

    /** Refusal: connection (invalid token, unknown protocol) or workflow (missing, inactive). {@code correlationId} is empty for a connection refusal. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(String correlationId, String message) implements FermionMsg {}
}
