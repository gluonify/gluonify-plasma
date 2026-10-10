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
 * {@link PayloadCodec} de Jackson 2 ({@code com.fasterxml.jackson}, 2.15 ou plus pour les records). Le {@code JavaTimeModule}
 * ({@code jackson-datatype-jsr310}) y est enregistré s'il est présent ; sans lui Jackson 2 refuse les {@code LocalDate}. N'est chargé que si
 * Jackson 2 est présent.
 */
public final class Jackson2Codec implements PayloadCodec {
    private final ObjectMapper mapper;

    private Jackson2Codec(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Un codec sur le mapper de l'application, tel quel (le client n'y change rien). */
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
     * Isolé pour que {@code JavaTimeModule}, facultatif, ne soit chargé que s'il est présent : la JVM vérifie une classe en entier dès qu'elle
     * l'utilise, donc cette classe ne doit contenir que ce qui a besoin du module (elle n'est jamais touchée sans lui).
     */
    private static final class JavaTime {
        static void register(final JsonMapper.Builder builder) {
            builder.addModule(new JavaTimeModule());
        }
    }
}
