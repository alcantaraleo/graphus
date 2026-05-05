package io.graphus.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Extracts Maven reactor {@code module} directory names from a parent aggregator {@code pom.xml}.
 */
public final class MavenModuleParser {

    private MavenModuleParser() {
    }

    public static List<String> parseModuleNames(Path pomFile) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setNamespaceAware(true);

            Document document = factory.newDocumentBuilder().parse(pomFile.toFile());
            Element root = document.getDocumentElement();
            if (root == null) {
                return Collections.emptyList();
            }
            Node modulesContainer = localChildNamed(root, "modules");
            if (modulesContainer == null) {
                return Collections.emptyList();
            }

            NodeList nodes = modulesContainer.getChildNodes();
            List<String> modules = new ArrayList<>();
            for (int index = 0; index < nodes.getLength(); index++) {
                Node candidate = nodes.item(index);
                if (candidate instanceof Element element && elementNameMatches(element.getLocalName(), element.getTagName(), "module")) {
                    String text = safeTrim(element.getTextContent());
                    if (!text.isEmpty()) {
                        modules.add(text.replace('\\', '/'));
                    }
                }
            }
            return List.copyOf(modules);
        } catch (Exception e) {
            throw new IOException("Failed to parse Maven modules from " + pomFile, e);
        }
    }

    private static boolean elementNameMatches(String localName, String tagName, String expected) {
        if (expected.equals(localName)) {
            return true;
        }
        if (tagName.equals(expected)) {
            return true;
        }
        String bare = bareLocalName(tagName);
        return expected.equalsIgnoreCase(bare);
    }

    private static Node localChildNamed(Element parent, String desired) {
        NodeList nl = parent.getChildNodes();
        for (int index = 0; index < nl.getLength(); index++) {
            Node child = nl.item(index);
            if (!(child instanceof Element element)) {
                continue;
            }
            if (elementNameMatches(element.getLocalName(), element.getTagName(), desired)) {
                return element;
            }
        }
        return null;
    }

    private static String bareLocalName(String tagName) {
        int colonIndex = tagName.indexOf(':');
        if (colonIndex == -1) {
            return tagName;
        }
        return tagName.substring(colonIndex + 1);
    }

    private static String safeTrim(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
