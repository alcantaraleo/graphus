package io.graphus.model;

public abstract class SymbolNode {

    private final String id;
    private final SymbolKind kind;
    private final String filePath;
    private final int line;

    protected SymbolNode(String id, SymbolKind kind, String filePath, int line) {
        this.id = id;
        this.kind = kind;
        this.filePath = filePath;
        this.line = line;
    }

    public String getId() {
        return id;
    }

    public SymbolKind getKind() {
        return kind;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getLine() {
        return line;
    }
}
