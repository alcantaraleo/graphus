package io.graphus.model;

/**
 * Represents a Gradle or Maven module as a node in the call graph. Used to model
 * inter-module {@code MODULE_DEPENDS_ON} edges discovered from build files.
 *
 * <p>The node ID follows the convention {@code module:<module-name>} where
 * {@code module-name} matches {@link ModuleDescriptor#name()}.
 */
public final class ModuleNode extends SymbolNode {

    public static final String ID_PREFIX = "module:";

    private final String moduleName;

    public ModuleNode(String moduleName) {
        super(ID_PREFIX + moduleName, SymbolKind.MODULE, "", 0);
        if (moduleName == null || moduleName.isBlank()) {
            throw new IllegalArgumentException("moduleName must not be blank");
        }
        this.moduleName = moduleName;
    }

    public String getModuleName() {
        return moduleName;
    }

    public static String idFor(String moduleName) {
        return ID_PREFIX + moduleName;
    }
}
