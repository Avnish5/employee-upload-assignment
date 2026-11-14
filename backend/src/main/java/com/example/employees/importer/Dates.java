package com.example.employees.importer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.regex.Pattern;

/** Canonical date format DD-MM-YYYY, parsed strictly (no 31-02 rollover, no other layouts). */
public final class Dates {

    public static final String CANONICAL = "DD-MM-YYYY";

    private static final Pattern SHAPE = Pattern.compile("\\d{2}-\\d{2}-\\d{4}");
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT);

    private Dates() {
    }

    /** True if the value has the DD-MM-YYYY shape (digits and dashes), regardless of calendar validity. */
    public static boolean hasCanonicalShape(String value) {
        return SHAPE.matcher(value).matches();
    }

    /** Parses a value that already has the canonical shape; empty if it is not a real calendar date. */
    public static Optional<LocalDate> parse(String value) {
        try {
            return Optional.of(LocalDate.parse(value, FORMAT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static String format(LocalDate date) {
        return date.format(FORMAT);
    }
}
