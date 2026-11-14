package com.example.employees.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** JDBC batch writes and key lookups for the import pipeline (plain JDBC: JPA identity inserts cannot batch). */
@Repository
public class ImportRepository implements Deduplicator.Lookup {

    /** A rejected row ready to be written to upload_error. */
    public record RejectedRow(RawRow raw, List<RowError> errors) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public ImportRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public List<ExistingEmployee> find(Set<String> nameDobKeys, Set<String> mobiles, Set<String> emails) {
        List<String> conditions = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (!nameDobKeys.isEmpty()) {
            conditions.add("e.name_dob_key IN (:nameDob)");
            params.addValue("nameDob", nameDobKeys);
        }
        if (!mobiles.isEmpty()) {
            conditions.add("e.mobile IN (:mobiles)");
            params.addValue("mobiles", mobiles);
        }
        if (!emails.isEmpty()) {
            conditions.add("e.email IN (:emails)");
            params.addValue("emails", emails);
        }
        if (conditions.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT e.*, u.source FROM employee e JOIN upload u ON u.id = e.upload_id WHERE "
                + conditions.stream().collect(Collectors.joining(" OR "));
        return jdbc.query(sql, params, (rs, i) -> new ExistingEmployee(
                rs.getLong("id"),
                rs.getLong("upload_id"),
                rs.getString("source"),
                rs.getInt("source_row"),
                new NormalizedRow(
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getDate("dob").toLocalDate(),
                        rs.getString("gender"),
                        rs.getString("mobile"),
                        rs.getString("email"),
                        rs.getString("department"),
                        rs.getString("designation"),
                        rs.getDate("date_of_joining").toLocalDate())));
    }

    public void insertEmployees(long uploadId, List<Deduplicator.Candidate> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(clock.instant());
        SqlParameterSource[] batch = rows.stream().map(c -> {
            NormalizedRow r = c.row();
            return new MapSqlParameterSource()
                    .addValue("firstName", r.firstName())
                    .addValue("lastName", r.lastName())
                    .addValue("dob", Date.valueOf(r.dob()))
                    .addValue("gender", r.gender())
                    .addValue("mobile", r.mobile())
                    .addValue("email", r.email())
                    .addValue("department", r.department())
                    .addValue("designation", r.designation())
                    .addValue("doj", Date.valueOf(r.dateOfJoining()))
                    .addValue("nameDobKey", r.nameDobKey())
                    .addValue("uploadId", uploadId)
                    .addValue("sourceRow", c.raw().rowNumber())
                    .addValue("createdAt", now);
        }).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO employee (first_name, last_name, dob, gender, mobile, email, department, designation,
                                      date_of_joining, name_dob_key, upload_id, source_row, created_at)
                VALUES (:firstName, :lastName, :dob, :gender, :mobile, :email, :department, :designation,
                        :doj, :nameDobKey, :uploadId, :sourceRow, :createdAt)""", batch);
    }

    public void insertErrors(long uploadId, List<RejectedRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = rows.stream().map(r -> new MapSqlParameterSource()
                .addValue("uploadId", uploadId)
                .addValue("rowNum", r.raw().rowNumber())
                .addValue("codes", r.errors().stream().map(e -> e.code().name()).distinct()
                        .collect(Collectors.joining(";")))
                .addValue("messages", truncate(r.errors().stream().map(RowError::message)
                        .collect(Collectors.joining("; ")), 2000))
                .addValue("raw", toJson(r.raw().values()))).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO upload_error (upload_id, row_num, error_codes, error_messages, raw_values)
                VALUES (:uploadId, :rowNum, :codes, :messages, :raw)""", batch);
    }

    private String toJson(List<String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
