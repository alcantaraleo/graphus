package io.graphus.model;

public final class UnresolvedNode extends SymbolNode {

    private final String expression;

    public UnresolvedNode(String id, String expression, String filePath, int line) {
        super(id, SymbolKind.UNRESOLVED, filePath, line);
        this.expression = expression;
    }

    public String getExpression() {
        return expression;
    }
}
