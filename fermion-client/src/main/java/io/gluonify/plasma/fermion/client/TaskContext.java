package io.gluonify.plasma.fermion.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/**
 * What a {@link SkillHandler} receives. Payload values come from JSON: the typed getters convert through Jackson. A missing parameter
 * or one of another type throws a clear {@link SkillFailureException} (errorKind {@code MISSING_PARAMETER} or {@code INVALID_PARAMETER});
 * {@link #optional} returns an {@link Optional}. {@link #progress(Map)} sends a structured progress event to fermion.
 *
 * @param globalTransactionId business transaction, or {@code null}
 * @param correlationId       instance of the task
 * @param action              skill of the task
 * @param payload             input variables
 * @param mapper              JSON mapper for the conversions
 * @param stepId              step of the task, or {@code null}
 * @param events              where {@link #progress(Map)} sends the events (never {@code null})
 * @author David CABILLIC
 */
public record TaskContext(String globalTransactionId, String correlationId, String action, Map<String, Object> payload, ObjectMapper mapper, String stepId,
                          Consumer<Map<String, Object>> events) {

    private static final Consumer<Map<String, Object>> NO_EVENTS = _ -> {
        // Outside a connection (tests, a handler called directly): events go nowhere.
    };

    /**
     * Normalises a {@code null} payload to empty and a {@code null} event sink to one that drops the events.
     *
     * @param globalTransactionId business transaction, or {@code null}
     * @param correlationId       instance of the task
     * @param action              skill of the task
     * @param payload             input variables
     * @param mapper              JSON mapper
     * @param stepId              step of the task, or {@code null}
     * @param events              event sink, or {@code null}
     */
    public TaskContext {
        payload = payload == null ? Map.of() : payload;
        events = events == null ? NO_EVENTS : events;
    }

    /**
     * Context whose progress events go nowhere.
     *
     * @param globalTransactionId business transaction, or {@code null}
     * @param correlationId       instance of the task
     * @param action              skill of the task
     * @param payload             input variables
     * @param mapper              JSON mapper
     * @param stepId              step of the task, or {@code null}
     * @deprecated kept for the SDK clients built before the event sink; use {@link #TaskContext(String, String, String, Map, ObjectMapper)}
     *             then {@link #withStepId(String)}
     */
    @Deprecated(since = "0.8.0")
    @SuppressWarnings("gluonify-native:GN13") // public SDK signature kept for compatibility, replaced by withStepId
    public TaskContext(final String globalTransactionId, final String correlationId, final String action, final Map<String, Object> payload,
                       final ObjectMapper mapper, final String stepId) {
        this(globalTransactionId, correlationId, action, payload, mapper, stepId, null);
    }

    /**
     * Context without step, whose progress events go nowhere.
     *
     * @param globalTransactionId business transaction, or {@code null}
     * @param correlationId       instance of the task
     * @param action              skill of the task
     * @param payload             input variables
     * @param mapper              JSON mapper
     */
    public TaskContext(final String globalTransactionId, final String correlationId, final String action, final Map<String, Object> payload, final ObjectMapper mapper) {
        this(globalTransactionId, correlationId, action, payload, mapper, null, null);
    }

    /**
     * The same context for another step.
     *
     * @param step step of the task, or {@code null}
     * @return a copy of this context whose {@link #stepId()} is {@code step}
     */
    public TaskContext withStepId(final String step) {

        return new TaskContext(globalTransactionId, correlationId, action, payload, mapper, step, events);
    }

    /**
     * Sends a structured progress event (phase, tool call, cost so far) to fermion, which keeps it in the execution's timeline (bounded: 4 KB
     * per event, a number per step) and counts it as a heartbeat. An empty or {@code null} event is ignored. Never put a secret in it.
     *
     * @param data the event
     */
    public void progress(final Map<String, Object> data) {
        if (data != null && !data.isEmpty()) {
            events.accept(data);
        }
    }

    /** Organization of the execution, set by fermion (never taken from the client payload), or {@code null}. */
    public String organization() {
        final Object value = payload.get("organization");
        return value == null ? null : value.toString();
    }

    public boolean has(final String key) {
        return payload.get(key) != null;
    }

    /** Raw value or {@code null}. */
    public Object get(final String key) {
        return payload.get(key);
    }

    /** Value converted to {@code type}, required. */
    public <T> T get(final String key, final Class<T> type) {
        return optional(key, type).orElseThrow(() -> missing(key));
    }

    public <T> Optional<T> optional(final String key, final Class<T> type) {
        final Object value = payload.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        try {
            return Optional.ofNullable(mapper.convertValue(value, type));
        }
        catch (final RuntimeException e) {
            throw invalid(key, type.getSimpleName(), e);
        }
    }

    public String getString(final String key) {
        final Object value = payload.get(key);
        if (value == null) {
            throw missing(key);
        }
        return value.toString();
    }

    public long getLong(final String key) {
        final Object value = payload.get(key);
        if (value == null) {
            throw missing(key);
        }
        if (value instanceof final Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        }
        catch (final NumberFormatException e) {
            throw invalid(key, "long", e);
        }
    }

    public boolean getBoolean(final String key) {
        final Object value = payload.get(key);
        if (value == null) {
            throw missing(key);
        }
        if (value instanceof final Boolean b) {
            return b;
        }
        final String text = value.toString().trim();
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        throw invalid(key, "boolean", null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(final String key) {
        final Object value = payload.get(key);
        if (value == null) {
            throw missing(key);
        }
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        throw invalid(key, "map", null);
    }

    /** List whose every element is converted to {@code type}. */
    public <T> List<T> getList(final String key, final Class<T> type) {
        final Object value = payload.get(key);
        if (value == null) {
            throw missing(key);
        }
        try {
            return mapper.convertValue(value, mapper.getTypeFactory().constructCollectionType(List.class, type));
        }
        catch (final RuntimeException e) {
            throw invalid(key, "list of " + type.getSimpleName(), e);
        }
    }

    private SkillFailureException missing(final String key) {
        return new SkillFailureException("MISSING_PARAMETER", "Parameter '" + key + "' is missing from the payload of task " + action);
    }

    private SkillFailureException invalid(final String key, final String expected, final Throwable cause) {
        return new SkillFailureException("INVALID_PARAMETER", "Parameter '" + key + "' of task " + action + " is not a valid " + expected, cause);
    }

    @Override
    public String toString() {
        return "gtid=" + globalTransactionId + ",coid=" + correlationId + ",action=" + action + ",keys=" + payload.keySet();
    }
}
