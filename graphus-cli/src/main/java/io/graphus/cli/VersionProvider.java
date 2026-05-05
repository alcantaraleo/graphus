package io.graphus.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

final class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws IOException {
        Properties props = new Properties();
        try (InputStream in = VersionProvider.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return new String[]{"graphus (unknown version)"};
            }
            props.load(in);
        }
        return new String[]{"graphus " + props.getProperty("version", "unknown")};
    }
}
