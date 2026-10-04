package io.gluonify.plasma.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RendezvousTest {
    static final List<String> NODES = List.of("http://a:20000", "http://b:20000", "http://c:20000", "http://d:20000");

    @Test
    void memeClassementQuelqueSoitLOrdreDEntree() {
        assertEquals(Rendezvous.order("abc", NODES), Rendezvous.order("abc", List.of(NODES.get(3), NODES.get(1), NODES.get(0), NODES.get(2), NODES.get(1))));
        assertEquals(4, Rendezvous.order("abc", NODES).size(), "dédoublonné");
    }

    @Test
    void pickBorneParLeNombreDeNoeuds() {
        assertEquals(2, Rendezvous.pick("k", NODES, 2).size());
        assertEquals(4, Rendezvous.pick("k", NODES, 9).size());
        assertEquals(0, Rendezvous.pick("k", NODES, 0).size());
        assertEquals(List.of(), Rendezvous.pick("k", List.of(), 2));
    }

    @Test
    void unNoeudQuiPartNeDeplaceQueSesObjets() {
        List<String> without = NODES.subList(0, 3);
        int moved = 0;
        for (int i = 0; i < 400; i++) {
            String key = "obj-" + i;
            List<String> before = Rendezvous.pick(key, NODES, 2);
            List<String> after = Rendezvous.pick(key, without, 2);
            if (before.contains(NODES.get(3))) moved++;
            else assertEquals(before, after, "un objet qui n'était pas sur le nœud parti ne bouge pas");
        }
        assertTrue(moved > 100 && moved < 300, "environ la moitié des objets (k=2 sur 4 nœuds) : " + moved);
    }

    @Test
    void repartitionEquilibree() {
        var count = IntStream.range(0, 1000).boxed().flatMap(i -> Rendezvous.pick("o" + i, NODES, 1).stream())
                .collect(Collectors.groupingBy(n -> n, Collectors.counting()));
        assertEquals(Set.copyOf(NODES), count.keySet());
        count.values().forEach(c -> assertTrue(c > 150 && c < 350, "chaque nœud porte environ le quart : " + count));
    }
}
