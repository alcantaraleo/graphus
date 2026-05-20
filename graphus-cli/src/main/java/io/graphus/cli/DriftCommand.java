package io.graphus.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Diffs two snapshots produced by {@code graphus snapshot} and reports architectural changes —
 * what grew, shrunk, appeared, or disappeared.
 */
@Command(name = "drift",
        description = "Compare two graph snapshots and report architectural drift")
public final class DriftCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Parameters(paramLabel = "SNAPSHOT_A", index = "0",
            description = "Path to the baseline snapshot JSON (older)")
    private Path snapshotA;

    @Parameters(paramLabel = "SNAPSHOT_B", index = "1",
            description = "Path to the comparison snapshot JSON (newer)")
    private Path snapshotB;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> a = MAPPER.readValue(snapshotA.toFile(), MAP_TYPE);
        Map<String, Object> b = MAPPER.readValue(snapshotB.toFile(), MAP_TYPE);

        System.out.println(phase("Snapshot A: ") + label(a) + "  (" + snapshotA.getFileName() + ")");
        System.out.println(phase("Snapshot B: ") + label(b) + "  (" + snapshotB.getFileName() + ")");
        System.out.println(Ansi.style("---", Ansi.DIM));

        // ---- top-level counters ----
        reportDelta("Parsed files    ", a, b, "parsedFiles");
        reportDelta("Total nodes     ", a, b, "totalNodes");
        reportDelta("Total edges     ", a, b, "totalEdges");
        reportDelta("Total files     ", a, b, "totalFiles");
        reportDelta("Unresolved calls", a, b, "unresolvedCalls");
        reportDelta("Module count    ", a, b, "moduleCount");
        System.out.println(Ansi.style("---", Ansi.DIM));

        // ---- nodes by kind ----
        System.out.println(phase("Nodes by kind:"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kindA = (Map<String, Object>) a.getOrDefault("nodesByKind", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> kindB = (Map<String, Object>) b.getOrDefault("nodesByKind", Map.of());
        Set<String> allKinds = new TreeSet<>();
        allKinds.addAll(kindA.keySet());
        allKinds.addAll(kindB.keySet());
        for (String kind : allKinds) {
            int va = toInt(kindA.get(kind));
            int vb = toInt(kindB.get(kind));
            System.out.println("  " + String.format("%-16s", kind) + formatDelta(va, vb));
        }
        System.out.println(Ansi.style("---", Ansi.DIM));

        // ---- module changes ----
        @SuppressWarnings("unchecked")
        List<String> modulesA = (List<String>) a.getOrDefault("modules", List.of());
        @SuppressWarnings("unchecked")
        List<String> modulesB = (List<String>) b.getOrDefault("modules", List.of());
        Set<String> added = new TreeSet<>(modulesB);
        added.removeAll(modulesA);
        Set<String> removed = new TreeSet<>(modulesA);
        removed.removeAll(modulesB);
        if (!added.isEmpty() || !removed.isEmpty()) {
            System.out.println(phase("Module changes:"));
            for (String m : added) {
                System.out.println(Ansi.style("  + " + m, Ansi.GREEN));
            }
            for (String m : removed) {
                System.out.println(Ansi.style("  - " + m, Ansi.RED));
            }
            System.out.println(Ansi.style("---", Ansi.DIM));
        }

        // ---- top coupled node changes ----
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topA = (List<Map<String, Object>>) a.getOrDefault("topCoupledNodes", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topB = (List<Map<String, Object>>) b.getOrDefault("topCoupledNodes", List.of());
        reportTopCoupledChanges(topA, topB);

        return 0;
    }

    private static void reportDelta(String label, Map<String, Object> a, Map<String, Object> b,
            String key) {
        int va = toInt(a.get(key));
        int vb = toInt(b.get(key));
        System.out.println(String.format("%-18s", label) + formatDelta(va, vb));
    }

    private static String formatDelta(int before, int after) {
        int delta = after - before;
        String base = before + " → " + after;
        if (delta == 0) {
            return Ansi.style(base + "  (no change)", Ansi.DIM);
        }
        String sign = delta > 0 ? "+" : "";
        String deltaStr = "  (" + sign + delta + ")";
        return delta > 0
                ? base + Ansi.style(deltaStr, Ansi.GREEN)
                : base + Ansi.style(deltaStr, Ansi.RED);
    }

    private static void reportTopCoupledChanges(
            List<Map<String, Object>> topA, List<Map<String, Object>> topB) {
        Map<String, Integer> mapA = toIdEdgeMap(topA);
        Map<String, Integer> mapB = toIdEdgeMap(topB);

        List<String> newEntrants = new ArrayList<>();
        List<String> disappeared = new ArrayList<>();
        List<String> changed = new ArrayList<>();

        for (String id : mapB.keySet()) {
            if (!mapA.containsKey(id)) {
                newEntrants.add(id + " (" + mapB.get(id) + " incoming edges)");
            } else {
                int delta = mapB.get(id) - mapA.get(id);
                if (delta != 0) {
                    String sign = delta > 0 ? "+" : "";
                    changed.add(id + " " + sign + delta + " edges (" + mapA.get(id) + " → " + mapB.get(id) + ")");
                }
            }
        }
        for (String id : mapA.keySet()) {
            if (!mapB.containsKey(id)) {
                disappeared.add(id);
            }
        }

        if (!newEntrants.isEmpty() || !disappeared.isEmpty() || !changed.isEmpty()) {
            System.out.println(phase("Top coupled node changes:"));
            newEntrants.forEach(s -> System.out.println(Ansi.style("  + " + s, Ansi.GREEN)));
            disappeared.forEach(s -> System.out.println(Ansi.style("  - " + s, Ansi.RED)));
            changed.forEach(s -> System.out.println("  ~ " + s));
        }
    }

    private static Map<String, Integer> toIdEdgeMap(List<Map<String, Object>> list) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Map<String, Object> entry : list) {
            String id = (String) entry.get("id");
            int edges = toInt(entry.get("incomingEdges"));
            if (id != null) {
                map.put(id, edges);
            }
        }
        return map;
    }

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static String label(Map<String, Object> snapshot) {
        Object lbl = snapshot.get("label");
        Object at = snapshot.get("createdAt");
        String atStr = at != null ? at.toString() : "unknown";
        return lbl != null ? Ansi.style(lbl.toString(), Ansi.CYAN) + " @ " + atStr : atStr;
    }

    private static String phase(String value) {
        return Ansi.style(value, Ansi.BOLD);
    }
}
