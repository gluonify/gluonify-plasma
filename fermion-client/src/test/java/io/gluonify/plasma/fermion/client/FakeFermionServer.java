package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.FermionMsg;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.ObjectMapper;

/** Faux fermion : enregistre les trames reçues (par session) et peut envoyer des messages ou couper. */
@WebSocket(path = "/ws/fermion/{nodeId}")
public class FakeFermionServer {

    record Frame(int session, String nodeId, FermionMsg msg) {}

    static final List<Frame> FRAMES = new CopyOnWriteArrayList<>();
    static final List<WebSocketConnection> OPEN = new CopyOnWriteArrayList<>();
    static volatile boolean accepting = true;
    static volatile int sessions;
    static volatile String lastNodeId;

    @Inject
    ObjectMapper mapper;

    @OnOpen
    public void onOpen(final WebSocketConnection connection) {
        if (!accepting) {
            connection.close().subscribe().with(v -> { }, t -> { });
            return;
        }
        sessions++;
        lastNodeId = connection.pathParam("nodeId");
        OPEN.add(connection);
    }

    @OnClose
    public void onClose(final WebSocketConnection connection) {
        OPEN.remove(connection);
    }

    @OnTextMessage
    public void onMessage(final String raw, final WebSocketConnection connection) {
        if (!accepting || !OPEN.contains(connection)) {
            return;
        }
        FRAMES.add(new Frame(sessions, connection.pathParam("nodeId"), mapper.readValue(raw, FermionMsg.class)));
    }

    static void send(final ObjectMapper mapper, final FermionMsg msg) {
        final String text = mapper.writeValueAsString(msg);
        for (final WebSocketConnection c : OPEN) {
            c.sendTextAndAwait(text);
        }
    }

    static void dropAll() {
        for (final WebSocketConnection c : OPEN) {
            c.closeAndAwait();
        }
        OPEN.clear();
    }
}
