package io.graphus.parser;

import io.graphus.model.CallGraph;

public record ProjectParserResult(CallGraph callGraph, int parsedFiles, int unresolvedCalls) {
}
