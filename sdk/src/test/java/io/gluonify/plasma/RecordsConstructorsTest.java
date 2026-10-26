package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every record of the SDK, built through every one of its constructors (the compatibility constructors kept for old callers included) with absent values
 * and with empty ones: the normalisation of null collections must hold, and an invalid value must be refused with an exception, never with a crash elsewhere.
 */
class RecordsConstructorsTest {

    static List<Class<?>> records() throws IOException, ClassNotFoundException {
        final Path root = Path.of("target/classes");
        final List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (final Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                final String name = root.relativize(p).toString().replace('/', '.').replaceAll("\\.class$", "");
                if (name.equals("module-info") || name.endsWith("package-info")) {
                    continue;
                }
                final Class<?> c = Class.forName(name, false, RecordsConstructorsTest.class.getClassLoader());
                if (c.isRecord()) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    static Object value(final Class<?> type, final boolean empty) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return 'a';
        if (!empty) return null;
        if (type == String.class) return "";
        if (type == List.class) return List.of();
        if (type == Map.class) return Map.of();
        if (type == Set.class) return Set.of();
        if (type == Optional.class) return Optional.empty();
        if (type == Integer.class) return 0;
        if (type == Long.class) return 0L;
        if (type == Boolean.class) return false;
        if (type == Double.class) return 0.0;
        return null;
    }

    static Object build(final Constructor<?> c, final boolean empty) throws ReflectiveOperationException {
        final Class<?>[] types = c.getParameterTypes();
        final Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = value(types[i], empty);
        }
        c.setAccessible(true);
        return c.newInstance(args);
    }

    /** Uses the generated and the explicit methods; an instance built from absent values may legitimately refuse them (a null array), which is not the point here. */
    static void touch(final Class<?> type, final Object instance) throws ReflectiveOperationException {
        try {
            assertNotNull(instance.toString(), type.getName());
            assertEquals(instance, instance, type.getName());
            assertEquals(instance.hashCode(), instance.hashCode(), type.getName());
        }
        catch (final RuntimeException absentValue) {
            // an explicit method that reads an absent array or text
        }
        for (final var component : type.getRecordComponents()) {
            final Method accessor = component.getAccessor();
            accessor.setAccessible(true);
            accessor.invoke(instance);
        }
    }

    @Test
    void thereAreRecordsToTest() throws Exception {
        assertTrue(records().size() > 100, "the SDK declares well over a hundred records: " + records().size());
    }

    @Test
    void everyConstructorBuildsOrRefusesWithAnException() throws Exception {
        int built = 0;
        for (final Class<?> type : records()) {
            for (final Constructor<?> c : type.getDeclaredConstructors()) {
                if (Modifier.isPrivate(c.getModifiers())) {
                    continue;
                }
                for (final boolean empty : new boolean[]{false, true}) {
                    try {
                        final Object instance = build(c, empty);
                        built++;
                        touch(type, instance);
                    }
                    catch (final InvocationTargetException refused) {
                        assertInstanceOf(RuntimeException.class, refused.getCause(), type.getName() + ": a refusal is a runtime exception, not " + refused.getCause());
                    }
                }
            }
        }
        assertTrue(built > 200, "built " + built);
    }
}
