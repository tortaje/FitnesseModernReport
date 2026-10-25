package com.tortaje.fitnesse.plugins;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ResultParser {

    public record Scenario(String name, String status, List<String> tags, long ms, String error, String link) {}

    private ResultParser() {}

    public static List<Scenario> parse(String xml) {
        return parse(xml, null);
    }

    /** @param suitePath page names are made relative to it; defaults to the results' own rootPath */
    public static List<Scenario> parse(String xml, String suitePath) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // DOCTYPE is refused above, so no custom entities exist; FitNesse escapes page HTML as predefined entities
            factory.setAttribute("jdk.xml.maxGeneralEntitySizeLimit", "0");
            factory.setAttribute("jdk.xml.totalEntitySizeLimit", "0");
            Element root = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            String rootPath = suitePath != null && !suitePath.isBlank() ? suitePath : text(root, "rootPath");
            NodeList results = root.getElementsByTagName("result");
            List<Scenario> scenarios = new ArrayList<>();
            for (int i = 0; i < results.getLength(); i++) {
                Element result = (Element) results.item(i);
                boolean failed = number(result, "wrong") > 0;
                boolean errored = !failed && number(result, "exceptions") > 0;
                List<String> tags = Arrays.stream(text(result, "tags").split(","))
                        .map(String::trim).filter(t -> !t.isEmpty()).toList();
                scenarios.add(new Scenario(pageName(result, rootPath), failed ? "Fail" : errored ? "Error" : "Pass",
                        tags, number(result, "runTimeInMillis"), failed || errored ? ErrorExtractor.extract(text(result, "content")) : "",
                        text(result, "pageHistoryLink")));
            }
            return scenarios;
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed FitNesse results", e);
        }
    }

    public static String toJson(List<Scenario> scenarios) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < scenarios.size(); i++) {
            Scenario s = scenarios.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(quote(s.name())).append(",\"status\":").append(quote(s.status()))
                    .append(",\"tags\":").append(quoteAll(s.tags())).append(",\"ms\":").append(s.ms())
                    .append(",\"error\":").append(quote(s.error())).append(",\"link\":").append(quote(s.link())).append('}');
        }
        return sb.append(']').toString();
    }

    private static String quoteAll(List<String> values) {
        return "[" + String.join(",", values.stream().map(ResultParser::quote).toList()) + "]";
    }

    static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                case ' ' -> sb.append("\\u2028");
                case ' ' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Page path relative to the suite root, taken from the history link (relativePageName is the leaf only). */
    private static String pageName(Element result, String rootPath) {
        String leaf = text(result, "relativePageName");
        String fullPath = text(result, "pageHistoryLink");
        int query = fullPath.indexOf('?');
        if (query >= 0) {
            fullPath = fullPath.substring(0, query);
        }
        String prefix = rootPath + ".";
        if (!rootPath.isEmpty() && fullPath.startsWith(prefix) && fullPath.length() > prefix.length()) {
            return fullPath.substring(prefix.length());
        }
        return leaf;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        Node node = nodes.item(0);
        return node.getTextContent().trim();
    }

    private static long number(Element parent, String tag) {
        try {
            return Long.parseLong(text(parent, tag));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
