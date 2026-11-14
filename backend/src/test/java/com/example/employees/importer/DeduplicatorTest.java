package com.example.employees.importer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Identity rule: K1 name+dob, K2 mobile, K3 email; first occurrence in a file wins. */
class DeduplicatorTest {

    private final List<ExistingEmployee> db = new ArrayList<>();
    private final Deduplicator.Lookup lookup = (nameDob, mobiles, emails) -> db.stream()
            .filter(e -> nameDob.contains(e.row().nameDobKey()) || mobiles.contains(e.row().mobile())
                    || (e.row().email() != null && emails.contains(e.row().email())))
            .toList();
    private final Deduplicator dedup = new Deduplicator();

    private static NormalizedRow person(String first, String last, String mobile, String email) {
        return new NormalizedRow(first, last, LocalDate.of(1983, 3, 16), "M", mobile, email,
                "Water Supply", "Data Entry Operator", LocalDate.of(2022, 9, 22));
    }

    private static final NormalizedRow FATIMA = person("Fatima", "Nair", "6148179048", "fatima.nair239@example.org");

    private static Deduplicator.Candidate at(int row, NormalizedRow r) {
        String[] blank = new String[9];
        java.util.Arrays.fill(blank, "");
        return new Deduplicator.Candidate(RawRow.of(row, blank), r);
    }

    private List<Deduplicator.Outcome> classify(Deduplicator.Candidate... chunk) {
        return dedup.classify(List.of(chunk), lookup);
    }

    private static RowError rejection(Deduplicator.Outcome outcome) {
        assertThat(outcome).isInstanceOf(Deduplicator.Reject.class);
        return ((Deduplicator.Reject) outcome).error();
    }

    @Test
    void newPeopleAreInserted() {
        List<Deduplicator.Outcome> out = classify(at(2, FATIMA),
                at(3, person("Kiran", "Verma", "8656232279", null)));
        assertThat(out).allMatch(o -> o instanceof Deduplicator.Insert);
    }

    @Test
    void exactCopyLaterInFileIsDuplicateOfFirstRow() { // upload-1 rows 54 / 208
        List<Deduplicator.Outcome> out = classify(at(54, FATIMA), at(208, FATIMA));
        assertThat(out.get(0)).isInstanceOf(Deduplicator.Insert.class);
        assertThat(rejection(out.get(1))).isEqualTo(
                new RowError(ErrorCode.DUPLICATE_IN_FILE, "Duplicate of row 54 in this file"));
    }

    @Test
    void mobileTypoOfSamePersonIsAConflictNotANewEmployee() { // upload-1 rows 11 / 109
        NormalizedRow typo = person("Fatima", "Nair", "6148179548", "fatima.nair239@example.org");
        List<Deduplicator.Outcome> out = classify(at(11, FATIMA), at(109, typo));
        assertThat(rejection(out.get(1))).isEqualTo(new RowError(ErrorCode.CONFLICT,
                "Matches row 11 of this file on name+dob, email; differs in: mobile (6148179048 vs 6148179548)"));
    }

    @Test
    void duplicatesAreDetectedAcrossChunks() {
        classify(at(2, FATIMA));
        db.add(new ExistingEmployee(1, 7, "USER", 2, FATIMA)); // chunk 1 committed
        assertThat(rejection(classify(at(1500, FATIMA)).get(0)).code()).isEqualTo(ErrorCode.DUPLICATE_IN_FILE);
    }

    @Test
    void identicalToExistingEmployeeIsSkippedCaseInsensitively() { // idempotent re-upload
        db.add(new ExistingEmployee(60, 2, "USER", 11, FATIMA));
        NormalizedRow sameDifferentCase = person("FATIMA", "nair", "6148179048", "fatima.nair239@example.org");
        Deduplicator.Outcome out = classify(at(11, sameDifferentCase)).get(0);
        assertThat(out).isInstanceOf(Deduplicator.Skip.class);
        assertThat(((Deduplicator.Skip) out).existing().id()).isEqualTo(60);
    }

    @Test
    void changedDetailsOfExistingEmployeeIsAConflictNotAnUpdate() { // uploads are insert-only
        db.add(new ExistingEmployee(60, 1, "PRELOAD", 36, FATIMA));
        NormalizedRow promoted = new NormalizedRow("Fatima", "Nair", FATIMA.dob(), "M", "6148179048",
                "fatima.nair239@example.org", "Water Supply", "Supervisor", FATIMA.dateOfJoining());
        assertThat(rejection(classify(at(5, promoted)).get(0))).isEqualTo(new RowError(ErrorCode.CONFLICT,
                "Matches employee #60 (preloaded, row 36) on name+dob, mobile, email; "
                        + "differs in: designation (Data Entry Operator vs Supervisor)"));
    }

    @Test
    void sharedMobileWithDifferentPersonIsAConflict() {
        db.add(new ExistingEmployee(8, 2, "USER", 40, FATIMA));
        NormalizedRow other = person("Ravi", "Nair", "6148179048", null);
        RowError error = rejection(classify(at(3, other)).get(0));
        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).startsWith("Matches employee #8 (upload 2, row 40) on mobile; differs in: ");
    }

    @Test
    void keysPointingAtTwoDifferentEmployeesListBoth() {
        db.add(new ExistingEmployee(1, 2, "USER", 2, FATIMA));
        db.add(new ExistingEmployee(2, 2, "USER", 3, person("Kiran", "Verma", "8656232279", "kiran@example.org")));
        NormalizedRow mixed = person("Fatima", "Nair", "8656232279", "fatima.nair239@example.org");
        String message = rejection(classify(at(9, mixed)).get(0)).message();
        assertThat(message).contains("Matches employee #1 (upload 2, row 2) on name+dob, email")
                .contains("; Matches employee #2 (upload 2, row 3) on mobile");
    }

    @Test
    void repeatOfASkippedRowIsStillADuplicateInFile() {
        db.add(new ExistingEmployee(60, 2, "USER", 11, FATIMA));
        List<Deduplicator.Outcome> out = classify(at(11, FATIMA), at(12, FATIMA));
        assertThat(out.get(0)).isInstanceOf(Deduplicator.Skip.class);
        assertThat(rejection(out.get(1)).code()).isEqualTo(ErrorCode.DUPLICATE_IN_FILE);
    }

    @Test
    void lookupReceivesAllThreeKeySets() {
        List<Set<String>> captured = new ArrayList<>();
        new Deduplicator().classify(List.of(at(2, FATIMA)), (a, b, c) -> {
            captured.addAll(List.of(a, b, c));
            return List.of();
        });
        assertThat(captured).containsExactly(Set.of("fatima|nair|1983-03-16"), Set.of("6148179048"),
                Set.of("fatima.nair239@example.org"));
    }
}
