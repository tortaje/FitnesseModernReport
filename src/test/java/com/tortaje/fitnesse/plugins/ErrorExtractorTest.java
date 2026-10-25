package com.tortaje.fitnesse.plugins;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorExtractorTest {

    private static final String TABLE = "<table><tr class=\"slimRowTitle\"><td>script</td><td>test slim</td></tr>"
            + "<tr><td>check</td><td>echo int</td><td>7</td><td><span class=\"pass\">7</span></td></tr>"
            + "<tr><td>check</td><td>echo int</td><td>7</td><td>7 <span class=\"fail\">[7] expected [8]</span></td></tr></table>";

    @Test
    void extract_keepsOnlyFailingRowsWithTheirTableTitle() {
        String error = ErrorExtractor.extract("<p>Included page intro</p>" + TABLE);
        assertEquals("script test slim › check | echo int | 7 | 7 [7] expected [8]", error);
    }

    @Test
    void extract_reportsEveryFailureSeparatedByBlankLine() {
        String second = TABLE.replace("expected [8]", "expected [9]");
        String error = ErrorExtractor.extract(TABLE + second);
        assertEquals(2, error.split("\n\n").length, error);
    }

    @Test
    void extract_decodesEntitiesAndStripsMarkup() {
        String error = ErrorExtractor.extract("<table><tr><td>x</td><td><span class=\"error\">&lt;a href=&quot;t&quot;&gt; &amp; missing</span></td></tr></table>");
        assertEquals("x | <a href=\"t\"> & missing", error);
    }

    @Test
    void extract_withoutFailingRowFallsBackToShortPlainText() {
        String error = ErrorExtractor.extract("<p>Test system crashed</p>" + "<b>x</b> ".repeat(500));
        assertTrue(error.startsWith("Test system crashed"));
        assertTrue(error.length() <= 600, "length " + error.length());
        assertFalse(error.contains("<"));
    }

    @Test
    void extract_capsTheNumberOfReportedFailures() {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            html.append(TABLE.replace("expected [8]", "expected [" + i + "]"));
        }
        String error = ErrorExtractor.extract(html.toString());
        assertTrue(error.endsWith("... and 20 more"), error);
    }
}
