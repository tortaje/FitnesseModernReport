package com.tortaje.fitnesse.plugins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunProgressTest {

    @Test
    void pagesFromPartition_skipsTheHeaderAndBlankLines() {
        String tsv = "Page\tPartition\tTest System\tOrder\r\nS.A\t0\tslim\t0\r\n\r\nS.Sub.B\t0\tslim\t1\r\n";
        assertEquals(List.of("S.A", "S.Sub.B"), RunProgress.pagesFromPartition(tsv));
    }

    @Test
    void pagesFromPartition_ofAnEmptyListIsEmpty() {
        assertEquals(List.of(), RunProgress.pagesFromPartition("Page\tPartition\tTest System\tOrder\n"));
        assertEquals(List.of(), RunProgress.pagesFromPartition(""));
    }

    private static void addResult(Path history, String page, String file) throws IOException {
        Path dir = Files.createDirectories(history.resolve(page));
        Files.writeString(dir.resolve(file), "<testResults/>");
    }

    @Test
    void fromHistory_countsOnlyPagesWithAResultWrittenSinceTheRunStarted(@TempDir Path history) throws Exception {
        // results of an earlier run must not count
        addResult(history, "S.Old", "20200101000000_1_0_0_0.xml");
        Files.setLastModifiedTime(history.resolve("S.Old"), FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));

        long start = System.currentTimeMillis();
        IntSupplier completed = RunProgress.fromHistory(history, List.of("S.Old", "S.A", "S.B", "S.Missing"), start);
        assertEquals(0, completed.getAsInt());

        addResult(history, "S.A", "20261025120000_1_0_0_0.xml");
        assertEquals(1, completed.getAsInt());

        addResult(history, "S.B", "20261025120001_0_1_0_0.xml");
        assertEquals(2, completed.getAsInt());
    }

    @Test
    void fromHistory_withoutAHistoryDirectoryCountsNothing(@TempDir Path root) {
        IntSupplier completed = RunProgress.fromHistory(root.resolve("nope"), List.of("S.A"), System.currentTimeMillis());
        assertEquals(0, completed.getAsInt());
    }
}
