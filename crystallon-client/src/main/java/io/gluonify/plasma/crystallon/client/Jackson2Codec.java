package io.gluonify.plasma.crystallon.client;

import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.Objects;

/**
 * {@link PayloadCodec} for Jackson 2 ({@code com.fasterxml.jackson}, 2.15 or later for records). The {@code JavaTimeModule}
 * ({@code jackson-datatype-jsr310}) is registered on it if present; without it Jackson 2 rejects {@code LocalDate}. Loaded only if
 * Jackson 2 is present.
 */
public final class Jackson2Codec implements PayloadCodec {
    private final ObjectMapper mapper;

    private Jackson2Codec(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** A codec over the application's mapper, as is (the client changes nothing on it). */
    public static PayloadCodec of(final ObjectMapper mapper) {
        return new Jackson2Codec(Objects.requireNonNull(mapper, "mapper"));
    }

    static Jackson2Codec create() {
        final JsonMapper.Builder builder = JsonMapper.builder()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
        if (javaTimeModulePresent()) {
            JavaTime.register(builder);
        }
        return new Jackson2Codec(builder.build());
    }

    @Override
    public byte[] write(final Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        }
        catch (final IOException e) { // JsonProcessingException
            throw new IllegalArgumentException("cannot write the Crystallon payload as JSON: " + originalMessage(e), e);
        }
    }

    @Override
    public <T> T read(final byte[] json, final Class<T> type) {
        try {
            return mapper.readValue(json, type);
        }
        catch (final IOException e) {
            throw new IllegalArgumentException("cannot read JSON as " + type.getSimpleName() + ": " + originalMessage(e), e);
        }
    }

    private static String originalMessage(final IOException e) {
        return e instanceof com.fasterxml.jackson.core.JsonProcessingException p ? p.getOriginalMessage() : e.getMessage();
    }

    private static boolean javaTimeModulePresent() {
        try {
            Class.forName("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");
            return true;
        }
        catch (final ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Isolated so that {@code JavaTimeModule}, which is optional, is loaded only if present: the JVM verifies a whole class as soon as it
     * uses it, so this class must contain only what needs the module (it is never touched without it).
     */
    private static final class JavaTime {
        static void register(final JsonMapper.Builder builder) {
            builder.addModule(new JavaTimeModule());
        }
    }
}
