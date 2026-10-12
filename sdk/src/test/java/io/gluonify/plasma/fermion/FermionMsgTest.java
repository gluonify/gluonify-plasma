package io.gluonify.plasma.fermion;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FermionMsgTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private FermionMsg roundTrip(FermionMsg m) throws Exception {
        return json.readValue(json.writeValueAsString(m), FermionMsg.class);
    }

    @Test
    void everyMessageSurvivesJson() throws Exception {
        var skill = new SkillDescriptor("send-mail", List.of(new Translation(Language.EN, "Send a mail"), new Translation(Language.FR, "Envoyer un courriel")),
                List.of(SkillParam.of("to", "STRING")), List.of(SkillParam.optional("id", "LONG")));
        var wf = new WorkflowDescriptor("onboarding", List.of(new Translation(Language.DE, "Einarbeitung")), List.of());
        for (FermionMsg m : List.<FermionMsg>of(
                new FermionMsg.Auth("jwt"),
                new FermionMsg.Hello("app", "app-1", List.of(skill), List.of(wf), "http://a/q/openapi"),
                new FermionMsg.Start("onboarding", "c1", Map.of("k", "v")),
                new FermionMsg.Command("c1", "g1", "send-mail", Map.of("to", "x@example.org")),
                FermionMsg.Result.ok("c1", "send-mail", Map.of("id", 1)),
                FermionMsg.Result.failure("c1", "send-mail", "smtp down", "TRANSIENT"),
                new FermionMsg.Start("build", "c2", "g2", Map.of("repo", "shop"), "acme"),
                new FermionMsg.Command("c1", "g1", "BUP_DEPLOY", Map.of("environment", "QUA"), "Task_DeployQua"),
                FermionMsg.Result.ok(new FermionMsg.Command("c1", "g1", "BUP_DEPLOY", Map.of(), "Task_DeployQua"), Map.of("status", "passed")),
                FermionMsg.Progress.of(new FermionMsg.Command("c1", "g1", "BUP_NATIVE_BUILD", Map.of(), "Task_Build"), "linking"),
                new FermionMsg.Hello("bup", "bup-0", List.of(new SkillDescriptor("BUP_CUSTOMER_APPROVAL", List.of(), List.of(), List.of(), "P7D")), List.of(), null),
                new FermionMsg.Error("c1", "workflow inactive")))
            assertEquals(json.readTree(json.writeValueAsString(m)), json.readTree(json.writeValueAsString(roundTrip(m))), m.getClass().getSimpleName());
    }

    @Test
    void discriminatorIsT() throws Exception {
        assertTrue(json.writeValueAsString(new FermionMsg.Auth("x")).contains("\"t\":\"auth\""));
        assertEquals(FermionMsg.PROTOCOL, ((FermionMsg.Auth) json.readValue("{\"t\":\"auth\",\"token\":\"x\",\"protocol\":1}", FermionMsg.class)).protocol());
    }

    @Test
    void nullCollectionsBecomeEmptyAndUnknownFieldsAreIgnored() throws Exception {
        var h = (FermionMsg.Hello) json.readValue("{\"t\":\"hello\",\"appId\":\"a\",\"nodeId\":\"n\",\"future\":1}", FermionMsg.class);
        assertTrue(h.skills().isEmpty());
        assertTrue(h.usedWorkflows().isEmpty());
        assertTrue(new FermionMsg.Start("w", "c", null).payload().isEmpty());
        assertNull(new FermionMsg.Start("w", "c", Map.of()).globalTransactionId());
    }

    @Test
    void stepIdEtOrganisationSontFacultatifsEtReprisParLaReponse() throws Exception {
        // Un ancien nœud (Interfero) ignore stepId et ne l'envoie pas : les deux sens restent lisibles.
        var c = (FermionMsg.Command) json.readValue("{\"t\":\"command\",\"correlationId\":\"c\",\"action\":\"A\"}", FermionMsg.class);
        assertNull(c.stepId());
        var r = (FermionMsg.Result) json.readValue("{\"t\":\"result\",\"correlationId\":\"c\",\"action\":\"A\",\"success\":true}", FermionMsg.class);
        assertNull(r.stepId());
        assertFalse(json.writeValueAsString(FermionMsg.Result.ok("c", "A", Map.of())).contains("stepId"));
        var s = (FermionMsg.Start) json.readValue("{\"t\":\"start\",\"workflow\":\"w\",\"correlationId\":\"c\"}", FermionMsg.class);
        assertNull(s.organization());

        var cmd = new FermionMsg.Command("c", "g", "BUP_DEPLOY", Map.of(), "Task_DeploySbx");
        assertEquals("Task_DeploySbx", FermionMsg.Result.ok(cmd, Map.of()).stepId());
        var failed = FermionMsg.Result.failure(cmd, "boom", "TRANSIENT");
        assertEquals("Task_DeploySbx", failed.stepId());
        assertFalse(failed.success());
        assertEquals("g", failed.globalTransactionId());
    }

    @Test
    void battementEtDureeAttendueSontFacultatifs() throws Exception {
        var p = (FermionMsg.Progress) json.readValue("{\"t\":\"progress\",\"correlationId\":\"c\",\"action\":\"A\",\"stepId\":\"Task_A\"}", FermionMsg.class);
        assertEquals("Task_A", p.stepId());
        assertNull(p.message());
        assertTrue(json.writeValueAsString(p).contains("\"t\":\"progress\""));
        // Un nœud antérieur n'annonce pas de durée : le champ est absent du JSON et vaut null.
        var skill = new SkillDescriptor("A", List.of(), List.of(), List.of());
        assertNull(skill.expectedDuration());
        assertFalse(json.writeValueAsString(skill).contains("expectedDuration"));
        assertEquals("PT2H", json.readValue("{\"id\":\"B\",\"expectedDuration\":\"PT2H\"}", SkillDescriptor.class).expectedDuration());
    }
}
