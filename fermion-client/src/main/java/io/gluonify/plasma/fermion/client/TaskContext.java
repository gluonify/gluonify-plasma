package io.gluonify.plasma.fermion.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * What a {@link SkillHandler} receives. Payload values come from JSON: the typed getters convert through Jackson. A missing parameter
 * or one of another type throws a clear {@link SkillFailureException} (errorKind {@code MISSING_PARAMETER} or {@code INVALID_PARAMETER});
 * {@link #optional} returns an {@link Optional}.
 */
public record TaskContext(String globalTransactionId, String correlationId, String action, Map<String, Object> payload, ObjectMapper mapper, String stepId) {

    public TaskContext {
        payload = payload == null ? Map.of() : payload;
    }

    public TaskContext(final String globalTransactionId, final String correlationId, final String action, final Map<String, Object> payload, final ObjectMapper mapper) {
        this(globalTransactionId, correlationId, action, payload, mapper, null);
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
