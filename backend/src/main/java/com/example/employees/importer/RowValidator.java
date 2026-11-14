package com.example.employees.importer;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalizes and validates one row. Pure: no database access. Collects every error in the row,
 * not just the first, so the admin can fix a row in one pass.
 */
@Component
public class RowValidator {

    static final int MAX_TEXT = 100;
    static final int MAX_EMAIL = 254;
    static final LocalDate MIN_DOB = LocalDate.of(1900, 1, 1);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NAME = Pattern.compile("\\p{L}[\\p{L}\\p{M} '.\\-]*");
    private static final Pattern MOBILE = Pattern.compile("[0-9]{10}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final Clock clock;

    public RowValidator(Clock clock) {
        this.clock = clock;
    }

    public sealed interface Result permits Valid, Invalid {
    }

    public record Valid(NormalizedRow row) implements Result {
    }

    public record Invalid(List<RowError> errors) implements Result {
    }

    public Result validate(RawRow raw) {
        if (raw.hasColumnCountMismatch()) {
            return new Invalid(List.of(new RowError(ErrorCode.COLUMN_COUNT,
                    "Expected " + raw.expectedFieldCount() + " fields but found " + raw.fieldCount()
                            + "; values cannot be matched to columns (shown in file order)")));
        }
        List<RowError> errors = new ArrayList<>();

        String firstName = name(raw, Column.FIRST_NAME, errors);
        String lastName = name(raw, Column.LAST_NAME, errors);
        LocalDate dob = date(raw, Column.DOB, errors);

        String gender = required(raw, Column.GENDER, errors);
        if (gender != null) {
            gender = gender.toUpperCase(Locale.ROOT);
            if (!gender.equals("F") && !gender.equals("M")) {
                errors.add(format("gender must be F or M (got '" + gender + "')"));
            }
        }

        String mobile = required(raw, Column.MOBILE, errors);
        if (mobile != null && !MOBILE.matcher(mobile).matches()) {
            errors.add(format("mobile must be exactly 10 digits (got '" + mobile + "')"));
        }

        String email = clean(raw.get(Column.EMAIL)).toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            email = null;
        } else if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
            errors.add(format("email is not a valid email address (got '" + email + "')"));
        }

        String department = text(raw, Column.DEPARTMENT, errors);
        String designation = text(raw, Column.DESIGNATION, errors);
        LocalDate doj = date(raw, Column.DATE_OF_JOINING, errors);

        LocalDate today = LocalDate.now(clock);
        if (dob != null && !dob.isBefore(today)) {
            errors.add(new RowError(ErrorCode.DATE_RANGE, "dob must be in the past"));
        }
        if (dob != null && dob.isBefore(MIN_DOB)) {
            errors.add(new RowError(ErrorCode.DATE_RANGE, "dob must be on or after " + Dates.format(MIN_DOB)));
        }
        if (dob != null && doj != null && !doj.isAfter(dob)) {
            errors.add(new RowError(ErrorCode.DATE_RANGE, "date_of_joining must be after dob"));
        }

        if (!errors.isEmpty()) {
            return new Invalid(errors);
        }
        return new Valid(new NormalizedRow(firstName, lastName, dob, gender, mobile, email,
                department, designation, doj));
    }

    /** Trim and collapse internal whitespace runs (including non-breaking spaces) to one space. */
    static String clean(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    private static String required(RawRow raw, Column column, List<RowError> errors) {
        String value = clean(raw.get(column));
        if (value.isEmpty()) {
            errors.add(new RowError(ErrorCode.REQUIRED, column.header() + " is required"));
            return null;
        }
        return value;
    }

    private static String text(RawRow raw, Column column, List<RowError> errors) {
        String value = required(raw, column, errors);
        if (value != null && value.length() > MAX_TEXT) {
            errors.add(format(column.header() + " must be at most " + MAX_TEXT + " characters"));
        }
        return value;
    }

    private static String name(RawRow raw, Column column, List<RowError> errors) {
        String value = text(raw, column, errors);
        if (value != null && value.length() <= MAX_TEXT && !NAME.matcher(value).matches()) {
            errors.add(format(column.header() + " contains invalid characters (allowed: letters, space, ' - .)"));
        }
        return value;
    }

    private static LocalDate date(RawRow raw, Column column, List<RowError> errors) {
        String value = required(raw, column, errors);
        if (value == null) {
            return null;
        }
        if (!Dates.hasCanonicalShape(value)) {
            errors.add(format(column.header() + " must be in " + Dates.CANONICAL + " format (got '" + value + "')"));
            return null;
        }
        Optional<LocalDate> date = Dates.parse(value);
        if (date.isEmpty()) {
            errors.add(new RowError(ErrorCode.INVALID_DATE,
                    column.header() + " is not a real calendar date (got '" + value + "')"));
            return null;
        }
        return date.get();
    }

    private static RowError format(String message) {
        return new RowError(ErrorCode.INVALID_FORMAT, message);
    }
}
