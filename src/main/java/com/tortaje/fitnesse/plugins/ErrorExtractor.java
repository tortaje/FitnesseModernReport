package com.tortaje.fitnesse.plugins;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reduces a FitNesse result page (HTML) to the table rows that failed or raised an error. */
final class ErrorExtractor {

    private static final int MAX_ERRORS = 10;
    private static final int MAX_FALLBACK_LENGTH = 500;
    private static final Pattern TABLE = Pattern.compile("(?is)<table[^>]*>(.*?)</table>");
    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");
    private static final Pattern FAILING = Pattern.compile("(?i)class=\"[^\"]*\\b(fail|error)\\b");

    private ErrorExtractor() {}

    static String extract(String html) {
        Set<String> errors = new LinkedHashSet<>();
        Matcher tables = TABLE.matcher(html);
        while (tables.find()) {
            String title = null;
            Matcher rows = ROW.matcher(tables.group(1));
            while (rows.find()) {
                String row = rows.group(1);
                boolean firstRow = title == null;
                String text = cells(row, " | ");
                if (firstRow) {
                    title = cells(row, " ");
                }
                if (FAILING.matcher(row).find()) {
                    errors.add(firstRow || title.isEmpty() ? text : title + " › " + text);
                }
            }
        }
        if (errors.isEmpty()) {
            String plain = text(html);
            return plain.length() > MAX_FALLBACK_LENGTH ? plain.substring(0, MAX_FALLBACK_LENGTH) : plain;
        }
        List<String> kept = new ArrayList<>(errors);
        if (kept.size() > MAX_ERRORS) {
            int more = kept.size() - MAX_ERRORS;
            kept = new ArrayList<>(kept.subList(0, MAX_ERRORS));
            kept.add("... and " + more + " more");
        }
        return String.join("\n\n", kept);
    }

    private static String cells(String row, String separator) {
        List<String> texts = new ArrayList<>();
        Matcher cells = CELL.matcher(row);
        while (cells.find()) {
            texts.add(text(cells.group(1)));
        }
        return String.join(separator, texts);
    }

    private static String text(String html) {
        String stripped = html.replaceAll("(?s)<[^>]*>", " ");
        return stripped.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }
}
