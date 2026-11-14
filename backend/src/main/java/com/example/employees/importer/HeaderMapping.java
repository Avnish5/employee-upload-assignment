package com.example.employees.importer;

import org.apache.commons.csv.CSVRecord;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the expected columns to their position in the file's header.
 * Columns are matched by name (case-insensitive, trimmed), so order does not matter;
 * unknown extra columns are ignored; any missing expected column rejects the file.
 */
public final class HeaderMapping {

    private final Map<Column, Integer> indexByColumn;
    private final int headerSize;

    private HeaderMapping(Map<Column, Integer> indexByColumn, int headerSize) {
        this.indexByColumn = indexByColumn;
        this.headerSize = headerSize;
    }

    public static HeaderMapping from(CSVRecord header) throws InvalidFileException {
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).replace("﻿", "").strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (positions.putIfAbsent(name, i) != null) {
                throw new InvalidFileException("Header contains column '" + name + "' more than once.");
            }
        }
        Map<Column, Integer> mapping = new EnumMap<>(Column.class);
        List<String> missing = new ArrayList<>();
        for (Column column : Column.values()) {
            Integer index = positions.get(column.header());
            if (index == null) {
                missing.add(column.header());
            } else {
                mapping.put(column, index);
            }
        }
        if (!missing.isEmpty()) {
            throw new InvalidFileException("Header is missing required column(s): " + String.join(", ", missing)
                    + ". Expected header: " + expectedHeader());
        }
        return new HeaderMapping(mapping, header.size());
    }

    public static String expectedHeader() {
        StringBuilder sb = new StringBuilder();
        for (Column c : Column.values()) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(c.header());
        }
        return sb.toString();
    }

    public RawRow toRawRow(int rowNumber, CSVRecord record) {
        if (record.size() != headerSize) {
            return new RawRow(rowNumber, record.toList(), record.size(), headerSize);
        }
        List<String> values = new ArrayList<>(Column.values().length);
        for (Column column : Column.values()) {
            values.add(record.get(indexByColumn.get(column)));
        }
        return new RawRow(rowNumber, values, record.size(), headerSize);
    }
}
