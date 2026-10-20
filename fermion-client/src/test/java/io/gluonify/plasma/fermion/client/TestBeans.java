package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.SkillParam;
import io.gluonify.plasma.fermion.Translation;
import io.gluonify.plasma.fermion.WorkflowDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

/** Test beans: token, skills, workflows used. */
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

    /** Long skill: waits for RELEASE to be run (10 s at most); proves that a long task does not block the others. */
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

    /** Skill that waits for its lock (observable heartbeats). */
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

    /** Skill that holds the heavy slot, reports its phase and stops when fermion cancels it. */
    @ApplicationScoped
    public static class Cancellable implements SkillHandler {
        static volatile java.util.concurrent.CountDownLatch STARTED = new java.util.concurrent.CountDownLatch(1);
        static volatile java.util.concurrent.CountDownLatch STOP = new java.util.concurrent.CountDownLatch(1);
        static volatile io.gluonify.plasma.fermion.FermionMsg.Cancel LAST_CANCEL;

        @Override
        public String action() {
            return "CANCELLABLE";
        }

        @Override
        public Map<String, Integer> requires() {
            return Map.of("heavy", 1);
        }

        @Override
        public Map<String, Object> handle(final TaskContext context) throws Exception {
            context.progress(Map.of("phase", "started"));
            context.progress(Map.of());
            STARTED.countDown();
            if (STOP.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new SkillFailureException(io.gluonify.plasma.fermion.FermionMsg.Cancel.INTERRUPTED, "stopped: " + LAST_CANCEL.reason());
            }
            return Map.of("finished", true);
        }

        @Override
        public void onCancel(final TaskContext context, final io.gluonify.plasma.fermion.FermionMsg.Cancel cancel) {
            LAST_CANCEL = cancel;
            STOP.countDown();
        }
    }
}
