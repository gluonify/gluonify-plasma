package io.gluonify.plasma.contracts;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.analysis.AnalysisReport;
import io.gluonify.plasma.usage.UsageUnit;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Native: each element type of a list in the SDK records (contracts, pricing, analysis findings, unit catalogue) has its ARRAY in
 * the SDK's {@code reachability-metadata.json}. Jackson introspects the list class ({@code ListN} de {@code List.copyOf} et de {@code Stream.toList},
 * whose constructor takes an {@code E[]}) and instantiates the array type: without the entry, reading fails in native only
 * ({@code MissingReflectionRegistrationError} on {@code ContractVersion[]}, seen by {@code e2e-contracts.sh}). Same reason as
 * {@code ContractNativeMetadataTest} in gluonify-higgs, which also keeps these entries in its own list.
 */
class ContractNativeMetadataTest {
    static final String METADATA = "/META-INF/native-image/io.gluonify.plasma/gluonify-plasma-sdk/reachability-metadata.json";

    static final Class<?>[] ROOTS = {Organization.class, PriceList.class, Plan.class, ContractVersion.class, Violation.class, Pricing.Section.class,
            Pricing.Statement.class, AnalysisReport.class, UsageUnit.class, io.gluonify.plasma.usage.UsageReport.class, io.gluonify.plasma.ws.Msg.UsageReport.class};

    @Test
    void listArraysRegistered() throws Exception {
        final String json;
        try (InputStream in = getClass().getResourceAsStream(METADATA)) {
            assertNotNull(in, METADATA);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final Set<String> missing = new TreeSet<>();
        for (final Class<?> element : listElements()) {
            final String name = element.getName() + "[]";
            if (!json.contains("\"" + name + "\"")) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "tableaux absents de " + METADATA + " : " + missing);
    }

    @Test
    void theWalkFindsTheKnownLists() {
        final Set<String> names = new TreeSet<>();
        for (final Class<?> c : listElements()) {
            names.add(c.getSimpleName());
        }
        assertTrue(names.containsAll(Set.of("ContractVersion", "PriceListVersion", "PriceLine", "PriceOverride", "Tier", "Finding", "Integer", "String", "UsageInstance", "AppShare")),
                names.toString());
    }

    /** Element types of the collections met while walking the record components from {@link #ROOTS} (Map values included). */
    static Set<Class<?>> listElements() {
        final Set<Class<?>> seen = new LinkedHashSet<>();
        final Set<Class<?>> elements = new LinkedHashSet<>();
        final Deque<Class<?>> todo = new ArrayDeque<>(List.of(ROOTS));
        while (!todo.isEmpty()) {
            final Class<?> c = todo.pop();
            if (!seen.add(c) || !c.isRecord()) {
                continue;
            }
            for (final RecordComponent rc : c.getRecordComponents()) {
                visit(rc.getGenericType(), todo, elements, false);
            }
        }
        return elements;
    }

    private static void visit(final Type t, final Deque<Class<?>> todo, final Set<Class<?>> elements, final boolean inCollection) {
        if (t instanceof Class<?>) {
            final Class<?> c = (Class<?>) t;
            if (inCollection) {
                elements.add(c);
            }
            if (c.isRecord()) {
                todo.push(c);
            }
        }
        else if (t instanceof ParameterizedType && ((ParameterizedType) t).getRawType() instanceof Class<?>) {
            final ParameterizedType p = (ParameterizedType) t;
            final Class<?> raw = (Class<?>) p.getRawType();
            final Type[] args = p.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                visit(args[0], todo, elements, true);
            }
            else if (Map.class.isAssignableFrom(raw)) {
                visit(args[1], todo, elements, false);
            }
        }
    }
}
