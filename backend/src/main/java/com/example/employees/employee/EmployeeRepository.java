package com.example.employees.employee;

import com.example.employees.importer.Dates;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class EmployeeRepository {

    private static final RowMapper<EmployeeView> VIEW_MAPPER = (rs, i) -> new EmployeeView(
            rs.getLong("id"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            Dates.format(rs.getDate("dob").toLocalDate()),
            rs.getString("gender"),
            rs.getString("mobile"),
            rs.getString("email"),
            rs.getString("department"),
            rs.getString("designation"),
            Dates.format(rs.getDate("date_of_joining").toLocalDate()),
            rs.getLong("upload_id"),
            rs.getInt("source_row"));

    private final NamedParameterJdbcTemplate jdbc;

    public EmployeeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<EmployeeView> find(String query, String department, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (query != null && !query.isBlank()) {
            where.append(" AND (CONCAT(first_name, ' ', last_name) LIKE :q OR mobile LIKE :q OR email LIKE :q)");
            params.addValue("q", "%" + escapeLike(query.trim()) + "%");
        }
        if (department != null && !department.isBlank()) {
            where.append(" AND department = :department");
            params.addValue("department", department.trim());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM employee" + where, params, Long.class);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<EmployeeView> items = jdbc.query(
                "SELECT * FROM employee" + where
                        + " ORDER BY last_name, first_name, id LIMIT :limit OFFSET :offset",
                params, VIEW_MAPPER);
        return new PageResponse<>(items, page, size, total == null ? 0 : total);
    }

    public List<String> departments() {
        return jdbc.getJdbcTemplate().queryForList(
                "SELECT DISTINCT department FROM employee ORDER BY department", String.class);
    }

    public long count() {
        Long n = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM employee", Long.class);
        return n == null ? 0 : n;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
