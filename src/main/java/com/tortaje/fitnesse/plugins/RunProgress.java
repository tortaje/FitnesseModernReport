package com.tortaje.fitnesse.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Follows a suite while it runs. FitNesse only sends its XML results once the whole suite is finished, but it writes
 * one history entry per test as soon as that test completes: counting them gives a live progress figure.
 */
final class RunProgress {

    private RunProgress() {}

    /** The tests a suite will run, from {@code ?partition&format=tsv} (a header line, then one line per page). */
    static List<String> pagesFromPartition(String tsv) {
        List<String> pages = new ArrayList<>();
        boolean header = true;
        for (String line : tsv.split("\r?\n")) {
            if (header) {
                header = false;
                continue;
            }
            String page = line.split("\t", 2)[0].trim();
            if (!page.isEmpty()) {
                pages.add(page);
            }
        }
        return pages;
    }

    /**
     * @return a supplier of the number of {@code pages} that got a history entry since {@code startMillis}.
     *         The comparison is done on whole seconds because some file systems do not store anything finer.
     */
    static IntSupplier fromHistory(Path historyDirectory, List<String> pages, long startMillis) {
        long startSecond = startMillis / 1000;
        return () -> {
            int completed = 0;
            for (String page : pages) {
                try {
                    Path directory = historyDirectory.resolve(page);
                    if (Files.isDirectory(directory) && Files.getLastModifiedTime(directory).toMillis() / 1000 >= startSecond) {
                        completed++;
                    }
                } catch (IOException | RuntimeException e) {
                    // an unreadable entry is simply not counted
                }
            }
            return completed;
        };
    }
}
