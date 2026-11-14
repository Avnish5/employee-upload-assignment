package com.example.employees.importer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A validated, normalized employee row. Whitespace is already trimmed/collapsed, gender is upper-case,
 * email is lower-case (or null when absent).
 */
public record NormalizedRow(
        String firstName,
        String lastName,
        LocalDate dob,
        String gender,
        String mobile,
        String email,
        String department,
        String designation,
        LocalDate dateOfJoining) {

    /** K1 — person key: lower(first) | lower(last) | dob. */
    public String nameDobKey() {
        return firstName.toLowerCase(Locale.ROOT) + "|" + lastName.toLowerCase(Locale.ROOT) + "|" + dob;
    }

    /**
     * Fields that differ from {@code existing}, formatted as {@code field (existing vs this)}.
     * Text comparison is case-insensitive so "Revenue" and "REVENUE" are the same value.
     */
    public List<String> differencesFrom(NormalizedRow existing) {
        List<String> diffs = new ArrayList<>();
        diffText(diffs, "first_name", existing.firstName, firstName);
        diffText(diffs, "last_name", existing.lastName, lastName);
        diffDate(diffs, "dob", existing.dob, dob);
        diffText(diffs, "gender", existing.gender, gender);
        diffText(diffs, "mobile", existing.mobile, mobile);
        diffText(diffs, "email", existing.email, email);
        diffText(diffs, "department", existing.department, department);
        diffText(diffs, "designation", existing.designation, designation);
        diffDate(diffs, "date_of_joining", existing.dateOfJoining, dateOfJoining);
        return diffs;
    }

    public boolean sameAs(NormalizedRow existing) {
        return differencesFrom(existing).isEmpty();
    }

    private static void diffText(List<String> diffs, String field, String existing, String current) {
        boolean equal = existing == null ? current == null : current != null && existing.equalsIgnoreCase(current);
        if (!equal) {
            diffs.add(field + " (" + display(existing) + " vs " + display(current) + ")");
        }
    }

    private static void diffDate(List<String> diffs, String field, LocalDate existing, LocalDate current) {
        if (!Objects.equals(existing, current)) {
            diffs.add(field + " (" + Dates.format(existing) + " vs " + Dates.format(current) + ")");
        }
    }

    private static String display(String value) {
        return value == null || value.isEmpty() ? "empty" : value;
    }
}
