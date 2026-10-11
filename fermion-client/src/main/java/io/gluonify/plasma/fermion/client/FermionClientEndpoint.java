package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.FermionMsg;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocketClient;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Côté client du canal : exécute les {@code Command} (hors du thread d'événements) et répond par un {@code Result}, relaie les {@code Error}.
 * L'authentification et le {@code Hello} sont émis par {@link FermionClient} dès la connexion obtenue.
 */
@WebSocketClient(path = "/ws/fermion/{nodeId}")
public class FermionClientEndpoint {

    @Inject
    SkillHandlerRegistry registry;

    @Inject
    FermionClient client;

    @Inject
    ObjectMapper mapper;

    @OnTextMessage
    @Blocking
    public String onMessage(final String raw) {
        final FermionMsg msg;
        try {
            msg = mapper.readValue(raw, FermionMsg.class);
        }
        catch (final Exception e) {
            Log.errorv(e, "fermion-client=parse error={0}", e.getMessage());
            return null;
        }
        if (msg instanceof final FermionMsg.Error error) {
            client.fireWorkflowError(error.correlationId(), error.message());
            return null;
        }
        if (msg instanceof final FermionMsg.Command command) {
            return write(execute(command));
        }
        return null;
    }

    private FermionMsg.Result execute(final FermionMsg.Command command) {
        final SkillHandler handler = registry.get(command.action());
        if (handler == null) {
            Log.warnv("fermion-client=command action={0} no handler", command.action());
            return failure(command, "No handler for action " + command.action(), "NO_HANDLER");
        }
        try {
            final Map<String, Object> out = handler.handle(
                    new TaskContext(command.globalTransactionId(), command.correlationId(), command.action(), command.payload(), mapper, command.stepId()));
            return FermionMsg.Result.ok(command, out);
        }
        catch (final SkillFailureException e) {
            Log.warnv("fermion-client=task correlationId={0} action={1} errorKind={2} error={3}", command.correlationId(), command.action(), e.errorKind(), e.getMessage());
            return failure(command, e.getMessage(), e.errorKind());
        }
        catch (final Exception e) {
            Log.errorv(e, "fermion-client=task correlationId={0} action={1} error={2}", command.correlationId(), command.action(), e.getMessage());
            return failure(command, e.getMessage() == null ? e.getClass().getName() : e.getMessage(), null);
        }
    }

    private static FermionMsg.Result failure(final FermionMsg.Command command, final String error, final String errorKind) {
        return FermionMsg.Result.failure(command, error, errorKind);
    }

    @OnClose
    public void onClose(final WebSocketClientConnection connection) {
        client.connectionLost(connection);
    }

    @OnError
    public void onError(final Throwable t) {
        Log.warnv("fermion-client=error error={0}", t.getMessage());
    }

    private String write(final FermionMsg msg) {
        try {
            return mapper.writeValueAsString(msg);
        }
        catch (final Exception e) {
            Log.errorv(e, "fermion-client=write error={0}", e.getMessage());
            return null;
        }
    }
}
