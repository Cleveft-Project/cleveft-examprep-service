package com.cleveft.examprepservice.service;

import java.util.Locale;

/**
 * Course codes are free text a student types while a lecture is starting, so
 * the same course arrives as "EE355", "ee 355" and "EE-355". Grouping on the
 * raw string would split one course into three, and per-course readiness
 * computed over a third of the data is worse than no per-course readiness at
 * all — it looks authoritative and is wrong.
 *
 * <p>Normalising strips everything that is not a letter or digit and upper-cases
 * the rest, so all three collapse to {@code EE355}. The student's own spelling
 * is kept separately for display: they wrote "EE 355", and showing them
 * "EE355" would look like the app mangled their input.
 */
public final class CourseCodes {

    /** Lectures with no course code at all group under this. */
    public static final String UNGROUPED = "__UNGROUPED__";

    private CourseCodes() {
    }

    /**
     * @return a stable grouping key, or {@link #UNGROUPED} when there is no
     * usable code
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return UNGROUPED;
        }
        String stripped = raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return stripped.isEmpty() ? UNGROUPED : stripped;
    }

    public static boolean isUngrouped(String key) {
        return UNGROUPED.equals(key);
    }

    /** What to show the student: their own spelling, tidied of stray spacing. */
    public static String display(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Ungrouped";
        }
        return raw.trim().replaceAll("\\s+", " ");
    }
}
