package com.example.employees.upload;

import com.example.employees.importer.Column;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Streams the per-row error report as CSV: row_number, error codes and messages,
 * then the original raw values so the admin sees exactly what was in their file.
 */
@Component
public class ErrorReportWriter {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate streamingJdbc;
    private final ObjectMapper json;

    public ErrorReportWriter(DataSource dataSource, ObjectMapper json) {
        // MySQL Connector/J streams rows one at a time only with fetchSize = Integer.MIN_VALUE;
        // otherwise a 50k-error report would be fully buffered in memory.
        this.streamingJdbc = new JdbcTemplate(dataSource);
        this.streamingJdbc.setFetchSize(Integer.MIN_VALUE);
        this.json = json;
    }

    public static List<String> header() {
        List<String> header = new ArrayList<>(List.of("row_number", "error_codes", "error_messages"));
        for (Column c : Column.values()) {
            header.add(c.header());
        }
        return header;
    }

    public void write(long uploadId, Writer out) throws IOException {
        out.write('﻿'); // BOM so Excel opens the UTF-8 file with the right encoding
        CSVPrinter printer = new CSVPrinter(out, CSVFormat.RFC4180);
        printer.printRecord(header());
        try {
            streamingJdbc.query(
                    "SELECT row_num, error_codes, error_messages, raw_values FROM upload_error "
                            + "WHERE upload_id = ? ORDER BY row_num",
                    rs -> {
                        List<String> record = new ArrayList<>();
                        record.add(String.valueOf(rs.getInt("row_num")));
                        record.add(rs.getString("error_codes"));
                        record.add(safe(rs.getString("error_messages")));
                        try {
                            for (String value : json.readValue(rs.getString("raw_values"), STRING_LIST)) {
                                record.add(safe(value));
                            }
                            printer.printRecord(record);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    uploadId);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        printer.flush();
    }

    /**
     * Neutralises spreadsheet formula injection (a cell starting with = + - @ would be evaluated by Excel)
     * by prefixing a single quote. This is the only way report values differ from the input.
     */
    static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }
}
