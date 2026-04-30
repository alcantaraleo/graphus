package io.graphus.cli.install;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ToolAdapterRegistry {

    private final Map<String, ToolAdapter> adaptersByName;

    public ToolAdapterRegistry(List<ToolAdapter> adapters) {
        Map<String, ToolAdapter> indexedAdapters = new LinkedHashMap<>();
        for (ToolAdapter adapter : adapters) {
            indexedAdapters.put(normalize(adapter.name()), adapter);
        }
        this.adaptersByName = Map.copyOf(indexedAdapters);
    }

    public Optional<ToolAdapter> findByName(String requestedName) {
        return Optional.ofNullable(adaptersByName.get(normalize(requestedName)));
    }

    public List<String> supportedToolNames() {
        return adaptersByName.keySet().stream().sorted().toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
