package com.example.employees.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One case per data-quality problem found in the provided files, plus the field and date rules. */
class RowValidatorTest {

    private final RowValidator validator =
            new RowValidator(Clock.fixed(Instant.parse("2026-11-14T06:00:00Z"), ZoneOffset.UTC));

    // first_name, last_name, dob, gender, mobile, email, department, designation, date_of_joining
    private static String[] valid() {
        return new String[]{"Deepa", "Chirwa", "06-02-1974", "F", "8363595142", "deepa.chirwa102@example.org",
                "Sanitation", "Section Officer", "06-06-2020"};
    }

    private static String[] with(Column column, String value) {
        String[] row = valid();
        row[column.ordinal()] = value;
        return row;
    }

    private RowValidator.Result validate(String... values) {
        return validator.validate(RawRow.of(2, values));
    }

    private List<RowError> errors(String... values) {
        RowValidator.Result result = validate(values);
        assertThat(result).isInstanceOf(RowValidator.Invalid.class);
        return ((RowValidator.Invalid) result).errors();
    }

    private NormalizedRow ok(String... values) {
        RowValidator.Result result = validate(values);
        assertThat(result).as("expected valid but got %s", result).isInstanceOf(RowValidator.Valid.class);
        return ((RowValidator.Valid) result).row();
    }

    @Test
    void validRowIsNormalized() {
        NormalizedRow row = ok(valid());
        assertThat(row.firstName()).isEqualTo("Deepa");
        assertThat(row.dob()).isEqualTo(LocalDate.of(1974, 2, 6));
        assertThat(row.nameDobKey()).isEqualTo("deepa|chirwa|1974-02-06");
    }

    @Test
    void paddedValuesAreTrimmedAndCollapsed() { // upload-1 rows 181 and 451
        String[] row = valid();
        row[0] = "  Farida";
        row[1] = "Njoroge ";
        row[4] = " 7388606964 ";
        row[6] = "Public   Health"; // non-breaking space + run of spaces
        NormalizedRow n = ok(row);
        assertThat(n.firstName()).isEqualTo("Farida");
        assertThat(n.lastName()).isEqualTo("Njoroge");
        assertThat(n.mobile()).isEqualTo("7388606964");
        assertThat(n.department()).isEqualTo("Public Health");
    }

    @Test
    void genderIsCaseInsensitiveAndEmailLowerCasedAndOptional() {
        assertThat(ok(with(Column.GENDER, "m")).gender()).isEqualTo("M");
        assertThat(ok(with(Column.EMAIL, "Deepa.Chirwa@Example.ORG")).email()).isEqualTo("deepa.chirwa@example.org");
        assertThat(ok(with(Column.EMAIL, "   ")).email()).isNull();
    }

    @ParameterizedTest(name = "{0} = ''{1}'' -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            MOBILE          |               | REQUIRED          | mobile is required
            FIRST_NAME      |               | REQUIRED          | first_name is required
            DOB             |               | REQUIRED          | dob is required
            DEPARTMENT      |               | REQUIRED          | department is required
            DOB             | 17/06/1992    | INVALID_FORMAT    | dob must be in DD-MM-YYYY format (got '17/06/1992')
            DATE_OF_JOINING | 17-Jun-2023   | INVALID_FORMAT    | date_of_joining must be in DD-MM-YYYY format (got '17-Jun-2023')
            DOB             | 1990-02-15    | INVALID_FORMAT    | dob must be in DD-MM-YYYY format (got '1990-02-15')
            DOB             | 31-02-1990    | INVALID_DATE      | dob is not a real calendar date (got '31-02-1990')
            DATE_OF_JOINING | 45-13-2023    | INVALID_DATE      | date_of_joining is not a real calendar date (got '45-13-2023')
            GENDER          | X             | INVALID_FORMAT    | gender must be F or M (got 'X')
            MOBILE          | 98765         | INVALID_FORMAT    | mobile must be exactly 10 digits (got '98765')
            MOBILE          | +919876543210 | INVALID_FORMAT    | mobile must be exactly 10 digits (got '+919876543210')
            EMAIL           | not-an-email  | INVALID_FORMAT    | email is not a valid email address (got 'not-an-email')
            FIRST_NAME      | R2D2          | INVALID_FORMAT    | first_name contains invalid characters (allowed: letters, space, ' - .)
            DOB             | 01-01-1899    | DATE_RANGE        | dob must be on or after 01-01-1900
            DATE_OF_JOINING | 01-01-1970    | DATE_RANGE        | date_of_joining must be after dob
            """)
    void rejectsInvalidField(Column column, String value, ErrorCode code, String message) {
        assertThat(errors(with(column, value == null ? "" : value)))
                .containsExactly(new RowError(code, message));
    }

    @Test
    void futureDobIsRejected() { // also reports the joining date, which is now before dob (all errors listed)
        assertThat(errors(with(Column.DOB, "15-11-2026"))).containsExactly(
                new RowError(ErrorCode.DATE_RANGE, "dob must be in the past"),
                new RowError(ErrorCode.DATE_RANGE, "date_of_joining must be after dob"));
    }

    @Test
    void namesAllowApostrophesHyphensDotsAndNonLatinLetters() {
        ok(with(Column.LAST_NAME, "O'Brien-Smith Jr."));
        ok(with(Column.FIRST_NAME, "José"));
        ok(with(Column.FIRST_NAME, "प्रिया")); // Devanagari with combining vowel signs
    }

    @Test
    void futureJoiningDateAndUnderEighteenAreAllowed() { // deliberate: onboarding allows upcoming joiners; no minimum age
        ok(with(Column.DATE_OF_JOINING, "06-12-2026"));
        String[] young = valid();
        young[2] = "21-12-2000";
        young[8] = "10-07-2015"; // joined at 14, as in upload-1 row 319
        ok(young);
    }

    @Test
    void collectsAllErrorsInARow() {
        String[] row = valid();
        row[0] = "";
        row[2] = "17/06/1992";
        row[4] = "";
        assertThat(errors(row)).extracting(RowError::code)
                .containsExactly(ErrorCode.REQUIRED, ErrorCode.INVALID_FORMAT, ErrorCode.REQUIRED);
    }

    @Test
    void wrongFieldCountIsRejectedWithoutGuessingColumns() { // upload-1 row 426
        List<String> eight = Arrays.asList("Arjun", "Phiri", "11-10-1981", "M", "9916796222",
                "arjun.phiri276@example.org", "Data Entry Operator", "25-06-2024");
        RowValidator.Result result = validator.validate(new RawRow(426, eight, 8, 9));
        assertThat(((RowValidator.Invalid) result).errors()).containsExactly(new RowError(ErrorCode.COLUMN_COUNT,
                "Expected 9 fields but found 8; values cannot be matched to columns (shown in file order)"));
    }
}
