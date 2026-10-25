package com.tortaje.fitnesse.plugins;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultParserTest {

    static final String XML = """
            <testResults><result><counts><right>3</right><wrong>0</wrong><ignores>0</ignores><exceptions>0</exceptions></counts>
            <runTimeInMillis>120</runTimeInMillis><relativePageName>Suite.GoodTest</relativePageName><tags>api,smoke</tags><content>ok</content></result>
            <result><counts><right>1</right><wrong>2</wrong><ignores>0</ignores><exceptions>0</exceptions></counts>
            <runTimeInMillis>900</runTimeInMillis><relativePageName>Suite.BadTest</relativePageName><tags></tags><content>expected "x"</content></result></testResults>""";

    @Test
    void parse_extractsScenarios() {
        List<ResultParser.Scenario> list = ResultParser.parse(XML);
        assertEquals(2, list.size());
        assertEquals("Suite.GoodTest", list.get(0).name());
        assertEquals("Pass", list.get(0).status());
        assertEquals(List.of("api", "smoke"), list.get(0).tags());
        assertEquals(120, list.get(0).ms());
        assertEquals("Fail", list.get(1).status());
        assertEquals(List.of(), list.get(1).tags());
    }

    @Test
    void parse_malformedXmlThrows() {
        assertThrows(IllegalArgumentException.class, () -> ResultParser.parse("<not xml"));
    }

    @Test
    void toJson_escapesAndIsSafeInScript() {
        String json = ResultParser.toJson(List.of(new ResultParser.Scenario("a\"b", "Fail", List.of("t", "u"), 5, "</script>\n", "P?pageHistory&resultDate=1")));
        assertTrue(json.contains("a\\\"b"));
        assertTrue(json.contains("\"tags\":[\"t\",\"u\"]"), json);
        assertTrue(json.contains("\"link\":\"P?pageHistory\\u0026resultDate=1\""), json);
        assertFalse(json.contains("</script>"));
        assertTrue(json.contains("\\n"));
    }

    @Test
    void parse_stripsHtmlFromFailureContent() {
        String xml = "<testResults><result><counts><right>0</right><wrong>1</wrong><ignores>0</ignores><exceptions>0</exceptions></counts>"
                + "<runTimeInMillis>1</runTimeInMillis><relativePageName>P</relativePageName>"
                + "<content>&lt;table&gt;&lt;tr&gt;&lt;td&gt;7 &lt;span class=\"fail\"&gt;expected [8]&lt;/span&gt;&lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;</content></result></testResults>";
        assertEquals("7 expected [8]", ResultParser.parse(xml).get(0).error());
    }

    @Test
    void parse_acceptsVeryLargeHtmlContent() {
        String content = "&lt;td&gt;x&lt;/td&gt;".repeat(40_000);
        String xml = "<testResults><result><counts><right>0</right><wrong>1</wrong><ignores>0</ignores><exceptions>0</exceptions></counts>"
                + "<runTimeInMillis>1</runTimeInMillis><relativePageName>Big</relativePageName><content>" + content + "</content></result></testResults>";
        assertEquals("Fail", ResultParser.parse(xml).get(0).status());
    }

    @Test
    void parse_exceptionsWithoutFailuresAreErrors() {
        String xml = "<testResults><result><counts><right>1</right><wrong>0</wrong><ignores>0</ignores><exceptions>2</exceptions></counts>"
                + "<runTimeInMillis>1</runTimeInMillis><relativePageName>E</relativePageName><content>boom</content></result></testResults>";
        assertEquals("Error", ResultParser.parse(xml).get(0).status());
        assertEquals("boom", ResultParser.parse(xml).get(0).error());
    }

    @Test
    void parse_namesNestedPagesRelativeToTheSuite() {
        String xml = "<testResults><rootPath>My.Suite</rootPath>"
                + "<result><counts><right>1</right><wrong>0</wrong><ignores>0</ignores><exceptions>0</exceptions></counts><runTimeInMillis>1</runTimeInMillis>"
                + "<relativePageName>Leaf</relativePageName><pageHistoryLink>My.Suite.Sub.Leaf?pageHistory&amp;resultDate=2026</pageHistoryLink></result></testResults>";
        assertEquals("Sub.Leaf", ResultParser.parse(xml).get(0).name());
    }

    @Test
    void parse_withExplicitSuite_namesPagesRelativeToThatSuite() {
        String xml = "<testResults><rootPath>RerunLastFailures_My-Suite</rootPath>"
                + "<result><counts><right>0</right><wrong>1</wrong><ignores>0</ignores><exceptions>0</exceptions></counts><runTimeInMillis>1</runTimeInMillis>"
                + "<relativePageName>Leaf</relativePageName><pageHistoryLink>My.Suite.Sub.Leaf?pageHistory&amp;resultDate=2026</pageHistoryLink></result></testResults>";
        assertEquals("Sub.Leaf", ResultParser.parse(xml, "My.Suite").get(0).name());
    }

    @Test
    void parse_keepsTheExecutionHistoryLink() {
        String xml = "<testResults><rootPath>S</rootPath><result><counts><right>1</right><wrong>0</wrong><ignores>0</ignores><exceptions>0</exceptions></counts><runTimeInMillis>1</runTimeInMillis>"
                + "<relativePageName>T</relativePageName><pageHistoryLink>S.T?pageHistory&amp;resultDate=20261024170808</pageHistoryLink></result></testResults>";
        assertEquals("S.T?pageHistory&resultDate=20261024170808", ResultParser.parse(xml).get(0).link());
    }
}
