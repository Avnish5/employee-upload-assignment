package com.example.employees.importer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides insert / skip / reject for valid rows using the three match keys:
 * K1 name+dob, K2 mobile, K3 email (when present).
 * <p>
 * One instance per upload; it remembers rows accepted earlier in the same file (first occurrence wins).
 * Pure apart from the {@link Lookup} it is given, so it can be unit tested without a database.
 */
public final class Deduplicator {

    /** Finds existing employees matching any of the given keys. */
    @FunctionalInterface
    public interface Lookup {
        List<ExistingEmployee> find(Set<String> nameDobKeys, Set<String> mobiles, Set<String> emails);
    }

    public record Candidate(RawRow raw, NormalizedRow row) {
    }

    public sealed interface Outcome permits Insert, Skip, Reject {
        Candidate candidate();
    }

    public record Insert(Candidate candidate) implements Outcome {
    }

    /** Identical to an employee already in the system: idempotent no-op. */
    public record Skip(Candidate candidate, ExistingEmployee existing) implements Outcome {
    }

    public record Reject(Candidate candidate, RowError error) implements Outcome {
    }

    /** A row accepted earlier in this file; {@code existingId} is set when it was skipped as already loaded. */
    private record Seen(int rowNumber, NormalizedRow row, Long existingId) {

        String describe() {
            return "row " + rowNumber + " of this file"
                    + (existingId == null ? "" : " (already in the system as employee #" + existingId + ")");
        }
    }

    private enum Key {
        NAME_DOB("name+dob", NormalizedRow::nameDobKey),
        MOBILE("mobile", NormalizedRow::mobile),
        EMAIL("email", NormalizedRow::email);

        final String label;
        final Function<NormalizedRow, String> extract;

        Key(String label, Function<NormalizedRow, String> extract) {
            this.label = label;
            this.extract = extract;
        }
    }

    private final Map<Key, Map<String, Seen>> seenInFile = new HashMap<>();

    public Deduplicator() {
        for (Key key : Key.values()) {
            seenInFile.put(key, new HashMap<>());
        }
    }

    public List<Outcome> classify(List<Candidate> chunk, Lookup lookup) {
        Map<Key, Map<String, ExistingEmployee>> existing = loadExisting(chunk, lookup);
        List<Outcome> outcomes = new ArrayList<>(chunk.size());
        for (Candidate candidate : chunk) {
            outcomes.add(classifyOne(candidate, existing));
        }
        return outcomes;
    }

    private Outcome classifyOne(Candidate candidate, Map<Key, Map<String, ExistingEmployee>> existing) {
        NormalizedRow row = candidate.row();

        // 1. Against rows accepted earlier in this same file.
        Map<Seen, List<String>> fileMatches = matches(row, seenInFile);
        if (!fileMatches.isEmpty()) {
            if (fileMatches.size() == 1) {
                Seen first = fileMatches.keySet().iterator().next();
                if (row.sameAs(first.row())) {
                    return new Reject(candidate, new RowError(ErrorCode.DUPLICATE_IN_FILE,
                            "Duplicate of row " + first.rowNumber() + " in this file"));
                }
            }
            List<String> parts = new ArrayList<>();
            fileMatches.forEach((seen, keys) -> parts.add(conflictText(seen.describe(), keys, row, seen.row())));
            return new Reject(candidate, new RowError(ErrorCode.CONFLICT, String.join("; ", parts)));
        }

        // 2. Against employees already in the system.
        Map<ExistingEmployee, List<String>> dbMatches = matches(row, existing);
        if (!dbMatches.isEmpty()) {
            if (dbMatches.size() == 1) {
                ExistingEmployee match = dbMatches.keySet().iterator().next();
                if (row.sameAs(match.row())) {
                    remember(candidate, match.id());
                    return new Skip(candidate, match);
                }
            }
            List<String> parts = new ArrayList<>();
            dbMatches.forEach((emp, keys) -> parts.add(conflictText(emp.describe(), keys, row, emp.row())));
            return new Reject(candidate, new RowError(ErrorCode.CONFLICT, String.join("; ", parts)));
        }

        // 3. New employee.
        remember(candidate, null);
        return new Insert(candidate);
    }

    private static String conflictText(String target, List<String> keys, NormalizedRow row, NormalizedRow other) {
        List<String> diffs = row.differencesFrom(other);
        return "Matches " + target + " on " + String.join(", ", keys)
                + (diffs.isEmpty() ? "" : "; differs in: " + String.join(", ", diffs));
    }

    /** Distinct matched records (insertion-ordered), each with the labels of the keys that matched. */
    private static <T> Map<T, List<String>> matches(NormalizedRow row, Map<Key, Map<String, T>> index) {
        Map<T, List<String>> result = new LinkedHashMap<>();
        for (Key key : Key.values()) {
            String value = key.extract.apply(row);
            if (value == null) {
                continue;
            }
            T match = index.get(key).get(value);
            if (match != null) {
                result.computeIfAbsent(match, m -> new ArrayList<>()).add(key.label);
            }
        }
        return result;
    }

    private void remember(Candidate candidate, Long existingId) {
        Seen seen = new Seen(candidate.raw().rowNumber(), candidate.row(), existingId);
        for (Key key : Key.values()) {
            String value = key.extract.apply(candidate.row());
            if (value != null) {
                seenInFile.get(key).putIfAbsent(value, seen);
            }
        }
    }

    private static Map<Key, Map<String, ExistingEmployee>> loadExisting(Collection<Candidate> chunk, Lookup lookup) {
        Set<String> nameDob = new LinkedHashSet<>();
        Set<String> mobiles = new LinkedHashSet<>();
        Set<String> emails = new LinkedHashSet<>();
        for (Candidate c : chunk) {
            nameDob.add(c.row().nameDobKey());
            mobiles.add(c.row().mobile());
            if (c.row().email() != null) {
                emails.add(c.row().email());
            }
        }
        Map<Key, Map<String, ExistingEmployee>> index = new HashMap<>();
        for (Key key : Key.values()) {
            index.put(key, new HashMap<>());
        }
        if (chunk.isEmpty()) {
            return index;
        }
        for (ExistingEmployee e : lookup.find(nameDob, mobiles, emails)) {
            for (Key key : Key.values()) {
                String value = key.extract.apply(e.row());
                if (value != null) {
                    index.get(key).put(value, e);
                }
            }
        }
        return index;
    }
}
