package io.graphus.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.idea.KotlinFileType;
import org.jetbrains.kotlin.psi.KtFile;

/**
 * Wraps a {@link KotlinCoreEnvironment} so the rest of the parser can read {@code .kt} files
 * into {@link KtFile} PSI trees without each call site re-bootstrapping the compiler.
 *
 * <p>Implements {@link AutoCloseable} so {@link ProjectParser} disposes the environment when
 * the workspace is finished. {@code kotlin-compiler-embeddable} shades IntelliJ under the
 * {@code org.jetbrains.kotlin.com.intellij.*} package; only that prefix is used here.
 */
final class KotlinPsiEnvironment implements AutoCloseable {

    private final Disposable disposable = Disposer.newDisposable("graphus-kotlin-psi");
    private final KotlinCoreEnvironment environment;

    KotlinPsiEnvironment() {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.put(
                CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
                MessageCollector.Companion.getNONE());
        configuration.put(CommonConfigurationKeys.MODULE_NAME, "graphus-parser");
        this.environment = KotlinCoreEnvironment.createForProduction(
                disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES);
    }

    /**
     * Parses one {@code .kt} file from disk into a {@link KtFile}, backed by a
     * {@link LightVirtualFile} so {@link KtFile#getVirtualFilePath()} is non-null and visitors
     * can resolve repository-relative paths.
     */
    KtFile parse(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String absolutePath = file.toAbsolutePath().normalize().toString();
        LightVirtualFile virtualFile = new LightVirtualFile(
                absolutePath, KotlinFileType.INSTANCE, text);
        return (KtFile) PsiManager.getInstance(environment.getProject()).findFile(virtualFile);
    }

    @Override
    public void close() {
        Disposer.dispose(disposable);
    }
}
