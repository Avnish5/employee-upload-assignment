package com.example.employees.upload;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class UploadRepository {

    private static final RowMapper<UploadView> MAPPER = (rs, i) -> new UploadView(
            rs.getLong("id"),
            rs.getString("filename"),
            UploadSource.valueOf(rs.getString("source")),
            UploadStatus.valueOf(rs.getString("status")),
            rs.getString("error_message"),
            rs.getInt("total_rows"),
            rs.getInt("processed_rows"),
            rs.getInt("inserted_rows"),
            rs.getInt("skipped_rows"),
            rs.getInt("rejected_rows"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public UploadRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public long create(String filename, UploadSource source, int totalRows) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO upload (filename, source, status, total_rows, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, filename);
            ps.setString(2, source.name());
            ps.setString(3, UploadStatus.QUEUED.name());
            ps.setInt(4, totalRows);
            ps.setTimestamp(5, now());
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public void markProcessing(long id) {
        jdbc.update("UPDATE upload SET status = ?, started_at = ? WHERE id = ?",
                UploadStatus.PROCESSING.name(), now(), id);
    }

    public void addProgress(long id, int processed, int inserted, int skipped, int rejected) {
        jdbc.update("""
                UPDATE upload SET processed_rows = processed_rows + ?, inserted_rows = inserted_rows + ?,
                                  skipped_rows = skipped_rows + ?, rejected_rows = rejected_rows + ?
                WHERE id = ?""", processed, inserted, skipped, rejected, id);
    }

    public void markCompleted(long id) {
        jdbc.update("UPDATE upload SET status = ?, finished_at = ? WHERE id = ?",
                UploadStatus.COMPLETED.name(), now(), id);
    }

    public void markFailed(long id, String message) {
        jdbc.update("UPDATE upload SET status = ?, error_message = ?, finished_at = ? WHERE id = ?",
                UploadStatus.FAILED.name(), truncate(message), now(), id);
    }

    /** Uploads that were queued or running when the process stopped can never finish. */
    public int failInterrupted() {
        return jdbc.update("UPDATE upload SET status = ?, error_message = ?, finished_at = ? WHERE status IN (?, ?)",
                UploadStatus.FAILED.name(),
                "Processing was interrupted by a server restart. Please upload the file again; "
                        + "rows that already loaded will be skipped.",
                now(), UploadStatus.QUEUED.name(), UploadStatus.PROCESSING.name());
    }

    public Optional<UploadView> findById(long id) {
        return jdbc.query("SELECT * FROM upload WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<UploadView> findRecent(int limit) {
        return jdbc.query("SELECT * FROM upload ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 997) + "...";
    }
}
