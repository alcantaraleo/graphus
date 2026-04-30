package io.graphus.cli;

final class Ansi {

    static final String RESET = "\u001B[0m";
    static final String BOLD = "\u001B[1m";
    static final String DIM = "\u001B[2m";
    static final String RED = "\u001B[31m";
    static final String GREEN = "\u001B[32m";
    static final String YELLOW = "\u001B[33m";
    static final String CYAN = "\u001B[36m";

    static boolean ENABLED = System.console() != null;

    private Ansi() {
    }

    static String style(String text, String... codes) {
        if (!ENABLED || text == null || text.isEmpty() || codes == null || codes.length == 0) {
            return text;
        }

        StringBuilder builder = new StringBuilder();
        for (String code : codes) {
            if (code != null && !code.isEmpty()) {
                builder.append(code);
            }
        }

        if (builder.isEmpty()) {
            return text;
        }

        return builder.append(text).append(RESET).toString();
    }
}
