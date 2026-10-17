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
    void sameRankingWhateverTheInputOrder() {
        assertEquals(Rendezvous.order("abc", NODES), Rendezvous.order("abc", List.of(NODES.get(3), NODES.get(1), NODES.get(0), NODES.get(2), NODES.get(1))));
        assertEquals(4, Rendezvous.order("abc", NODES).size(), "deduplicated");
    }

    @Test
    void pickBoundedByTheNumberOfNodes() {
        assertEquals(2, Rendezvous.pick("k", NODES, 2).size());
        assertEquals(4, Rendezvous.pick("k", NODES, 9).size());
        assertEquals(0, Rendezvous.pick("k", NODES, 0).size());
        assertEquals(List.of(), Rendezvous.pick("k", List.of(), 2));
    }

    @Test
    void aDepartingNodeOnlyMovesItsObjects() {
        List<String> without = NODES.subList(0, 3);
        int moved = 0;
        for (int i = 0; i < 400; i++) {
            String key = "obj-" + i;
            List<String> before = Rendezvous.pick(key, NODES, 2);
            List<String> after = Rendezvous.pick(key, without, 2);
            if (before.contains(NODES.get(3))) moved++;
            else assertEquals(before, after, "an object that was not on the departed node does not move");
        }
        assertTrue(moved > 100 && moved < 300, "about half of the objects (k=2 on 4 nodes): " + moved);
    }

    @Test
    void balancedDistribution() {
        var count = IntStream.range(0, 1000).boxed().flatMap(i -> Rendezvous.pick("o" + i, NODES, 1).stream())
                .collect(Collectors.groupingBy(n -> n, Collectors.counting()));
        assertEquals(Set.copyOf(NODES), count.keySet());
        count.values().forEach(c -> assertTrue(c > 150 && c < 350, "each node carries about a quarter: " + count));
    }
}
