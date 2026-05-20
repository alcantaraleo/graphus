package io.graphus.parser;

import io.graphus.model.CallGraph;
import io.graphus.model.ModuleDescriptor;
import io.graphus.model.ModuleNode;
import io.graphus.model.WorkspaceDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Gradle build files to extract inter-module dependencies and adds synthetic
 * {@code MODULE_DEPENDS_ON} edges to the call graph.
 *
 * <p>Recognises both Kotlin DSL ({@code implementation(project(":module"))}) and
 * Groovy DSL ({@code implementation project(':module')}) syntax. Only production
 * dependency configurations are included — {@code testImplementation}, {@code testApi},
 * etc. are ignored.
 */
public final class GradleModuleDependencyParser {

    // Matches KTS: implementation(project(":module")) and Groovy: implementation project(':module')
    private static final Pattern PROJECT_DEP = Pattern.compile(
            "\\b(?:implementation|api|compileOnly|runtimeOnly)\\s*\\(?\\s*project\\s*\\(\\s*[\"']([^\"']+)[\"']"
    );

    private GradleModuleDependencyParser() {
    }

    /**
     * Adds a {@link ModuleNode} for every module in {@code workspace} to the graph, then
     * parses each module's {@code build.gradle.kts} (falling back to {@code build.gradle})
     * to add {@code module:A → module:B} edges for {@code implementation/api project(...)}
     * declarations.
     *
     * @return number of inter-module dependency edges added
     */
    public static int resolve(CallGraph callGraph, WorkspaceDescriptor workspace) throws IOException {
        for (ModuleDescriptor module : workspace.modules()) {
            callGraph.addNode(new ModuleNode(module.name()));
        }

        int edgesAdded = 0;
        for (ModuleDescriptor module : workspace.modules()) {
            List<String> depModuleNames = parseDependencies(module.root(), workspace);
            for (String depName : depModuleNames) {
                callGraph.addEdge(ModuleNode.idFor(module.name()), ModuleNode.idFor(depName));
                edgesAdded++;
            }
        }
        return edgesAdded;
    }

    /**
     * Parses production project-to-project dependencies from the build file at {@code moduleRoot}.
     */
    static List<String> parseDependencies(Path moduleRoot, WorkspaceDescriptor workspace) throws IOException {
        Path buildFile = moduleRoot.resolve("build.gradle.kts");
        if (!Files.isRegularFile(buildFile)) {
            buildFile = moduleRoot.resolve("build.gradle");
        }
        if (!Files.isRegularFile(buildFile)) {
            return List.of();
        }

        String content = Files.readString(buildFile, StandardCharsets.UTF_8);
        content = stripLineComments(content);

        List<String> deps = new ArrayList<>();
        Matcher matcher = PROJECT_DEP.matcher(content);
        while (matcher.find()) {
            String gradlePath = matcher.group(1);
            String relativePath = GradleSettingsParser.gradleIncludeToRelativePath(gradlePath);
            if (relativePath.isBlank()) {
                continue;
            }
            String moduleName = relativePath.replace("/", "__");
            if (moduleExists(moduleName, workspace)) {
                deps.add(moduleName);
            }
        }
        return List.copyOf(deps);
    }

    private static boolean moduleExists(String moduleName, WorkspaceDescriptor workspace) {
        for (ModuleDescriptor module : workspace.modules()) {
            if (module.name().equals(moduleName)) {
                return true;
            }
        }
        return false;
    }

    private static String stripLineComments(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.lines().toList()) {
            int idx = GradleSettingsParser.lineCommentStart(line);
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }
}
