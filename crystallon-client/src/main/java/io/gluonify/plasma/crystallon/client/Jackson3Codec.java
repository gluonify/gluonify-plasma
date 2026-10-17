package io.gluonify.plasma.crystallon.client;

import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/** {@link PayloadCodec} for Jackson 3 ({@code tools.jackson}); Jackson 3 handles {@code java.time} types without a module. Loaded only if Jackson 3 is present. */
public final class Jackson3Codec implements PayloadCodec {
    private final ObjectMapper mapper;

    private Jackson3Codec(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** A codec over the application's mapper, as is (the client changes nothing on it). */
    public static PayloadCodec of(final ObjectMapper mapper) {
        return new Jackson3Codec(Objects.requireNonNull(mapper, "mapper"));
    }

    static Jackson3Codec create() {
        return new Jackson3Codec(JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build());
    }

    @Override
    public byte[] write(final Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        }
        catch (final JacksonException e) {
            throw new IllegalArgumentException("cannot write the Crystallon payload as JSON: " + e.getOriginalMessage(), e);
        }
    }

    @Override
    public <T> T read(final byte[] json, final Class<T> type) {
        try {
            return mapper.readValue(json, type);
        }
        catch (final JacksonException e) {
            throw new IllegalArgumentException("cannot read JSON as " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }
}
