package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.SkillParam;
import io.gluonify.plasma.fermion.Translation;
import io.gluonify.plasma.fermion.WorkflowDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

/** Beans de test : jeton, compétences, workflows utilisés. */
public final class TestBeans {
    private TestBeans() {
    }

    @ApplicationScoped
    public static class Token implements FermionTokenProvider {
        @Override
        public String token() {
            return "test-token";
        }
    }

    @ApplicationScoped
    public static class Echo implements SkillHandler {
        @Override
        public String action() {
            return "ECHO";
        }

        @Override
        public List<SkillParam> inputs() {
            return List.of(SkillParam.of("text", "STRING"));
        }

        @Override
        public List<SkillParam> outputs() {
            return List.of(SkillParam.of("upper", "STRING"));
        }

        @Override
        public List<Translation> descriptions() {
            return SkillHandler.translations("Echo", "Écho", "Eco", "Eco", "Echo");
        }

        @Override
        public Map<String, Object> handle(final TaskContext context) {
            return Map.of("upper", context.getString("text").toUpperCase(), "thread", Thread.currentThread().getName());
        }
    }

    @ApplicationScoped
    public static class Fail implements SkillHandler {
        @Override
        public String action() {
            return "FAIL";
        }

        @Override
        public Map<String, Object> handle(final TaskContext context) {
            throw new SkillFailureException("STORAGE_UNAVAILABLE", "disk is gone");
        }
    }

    @ApplicationScoped
    public static class Boom implements SkillHandler {
        @Override
        public String action() {
            return "BOOM";
        }

        @Override
        public Map<String, Object> handle(final TaskContext context) {
            throw new IllegalStateException("boom");
        }
    }

    @ApplicationScoped
    public static class Usage implements WorkflowUsage {
        @Override
        public List<WorkflowDescriptor> getUsedWorkflows() {
            return List.of(new WorkflowDescriptor("order-flow", List.of(), List.of()));
        }
    }

    /** Compétence longue : attend que RELEASE soit exécutée (10 s au plus) ; prouve qu'une tâche longue ne bloque pas les autres. */
    public static final java.util.concurrent.CountDownLatch RELEASED = new java.util.concurrent.CountDownLatch(1);

    @ApplicationScoped
    public static class Slow implements SkillHandler {
        @Override
        public String action() {
            return "SLOW";
        }

        @Override
        public java.time.Duration expectedDuration() {
            return java.time.Duration.ofHours(2);
        }

        @Override
        public Map<String, Object> handle(final TaskContext context) throws Exception {
            return Map.of("released", RELEASED.await(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @ApplicationScoped
    public static class Release implements SkillHandler {
        @Override
        public String action() {
            return "RELEASE";
        }

        @Override
        public Map<String, Object> handle(final TaskContext context) {
            RELEASED.countDown();
            return Map.of();
        }
    }

    /** Compétence qui attend son verrou (battements observables). */
    @ApplicationScoped
    public static class Wait implements SkillHandler {
        static volatile java.util.concurrent.CountDownLatch GATE = new java.util.concurrent.CountDownLatch(0);

        @Override
        public String action() {
            return "WAIT";
        }

        @Override
        public Map<String, Object> handle(final TaskContext context) throws Exception {
            GATE.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return Map.of();
        }
    }
}
