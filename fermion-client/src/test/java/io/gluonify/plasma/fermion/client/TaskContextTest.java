package io.gluonify.plasma.fermion.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TaskContextTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private TaskContext ctx(final Map<String, Object> payload) {
        return new TaskContext("g", "c", "ACT", payload, mapper);
    }

    record Item(String name, int qty) {}

    @Test
    void typedGetters() {
        final TaskContext c = ctx(Map.of("s", "x", "l", 42, "ls", "7", "b", "true", "m", Map.of("k", 1), "items", List.of(Map.of("name", "a", "qty", 2))));
        assertEquals("x", c.getString("s"));
        assertEquals(42L, c.getLong("l"));
        assertEquals(7L, c.getLong("ls"));
        assertTrue(c.getBoolean("b"));
        assertEquals(1, c.getMap("m").get("k"));
        assertEquals(new Item("a", 2), c.getList("items", Item.class).get(0));
        assertEquals(new Item("a", 2), c.get("items", List.class).isEmpty() ? null : mapper.convertValue(c.getList("items", Map.class).get(0), Item.class));
        assertTrue(c.optional("nope", String.class).isEmpty());
        assertTrue(c.has("s"));
        assertFalse(c.has("nope"));
    }

    @Test
    void clearErrors() {
        final TaskContext c = ctx(Map.of("l", "abc", "b", "maybe", "m", "text"));
        assertEquals("MISSING_PARAMETER", assertThrows(SkillFailureException.class, () -> c.getString("x")).errorKind());
        assertEquals("MISSING_PARAMETER", assertThrows(SkillFailureException.class, () -> c.getLong("x")).errorKind());
        assertEquals("INVALID_PARAMETER", assertThrows(SkillFailureException.class, () -> c.getLong("l")).errorKind());
        assertEquals("INVALID_PARAMETER", assertThrows(SkillFailureException.class, () -> c.getBoolean("b")).errorKind());
        assertEquals("INVALID_PARAMETER", assertThrows(SkillFailureException.class, () -> c.getMap("m")).errorKind());
        assertTrue(assertThrows(SkillFailureException.class, () -> c.getString("x")).getMessage().contains("'x'"));
    }

    @Test
    void progressSendsOnlyNonEmptyEvents() {
        final List<Map<String, Object>> sent = new java.util.ArrayList<>();
        final TaskContext c = new TaskContext("g", "c", "ACT", Map.of(), mapper, "Task_A", sent::add);
        c.progress(Map.of("phase", "build"));
        c.progress(Map.of());
        c.progress(null);
        assertEquals(List.of(Map.of("phase", "build")), sent);
    }

    @Test
    void withStepIdCopiesTheContextForAnotherStep() {
        final TaskContext c = ctx(Map.of("k", 1)).withStepId("Task_B");
        assertEquals("Task_B", c.stepId());
        assertEquals("c", c.correlationId());
        assertEquals(1, c.get("k"));
        assertNotNull(c.events());
    }

    @Test
    void progressWithoutSinkGoesNowhere() {
        final TaskContext c = new TaskContext("g", "c", "ACT", Map.of(), mapper).withStepId("Task_A");
        c.progress(Map.of("phase", "build"));
        assertNotNull(c.events());
        assertEquals("Task_A", c.stepId());
    }
}
