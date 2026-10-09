package io.gluonify.plasma.fermion.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * Ce que reçoit un {@link SkillHandler}. Les valeurs de la charge utile viennent du JSON : les getters typés convertissent par Jackson. Un paramètre
 * absent ou d'un autre type lève une {@link SkillFailureException} claire (errorKind {@code MISSING_PARAMETER} ou {@code INVALID_PARAMETER}) ;
 * {@link #optional} renvoie un {@link Optional}.
 */
public record TaskContext(String globalTransactionId, String correlationId, String action, Map<String, Object> payload, ObjectMapper mapper) {

    public TaskContext {
        payload = payload == null ? Map.of() : payload;
    }

    public boolean has(final String key) {
        return payload.get(key) != null;
    }

    /** Valeur brute ou {@code null}. */
    public Object get(final String key) {
        return payload.get(key);
    }

    /** Valeur convertie en {@code type}, obligatoire. */
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

    /** Liste dont chaque élément est converti en {@code type}. */
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
