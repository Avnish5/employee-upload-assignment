package com.example.employees.importer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** Reading CSV files: strict UTF-8, CRLF or LF, BOM tolerated, blank lines kept so row numbers match Excel. */
public final class CsvFiles {

    // Empty lines are not skipped: they still occupy a spreadsheet row, so skipping them in the
    // parser would shift every later row number. Blank rows are skipped by the caller instead.
    static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
            .setIgnoreEmptyLines(false)
            .get();

    private CsvFiles() {
    }

    /** Opens a file for parsing; invalid UTF-8 surfaces as an exception rather than replacement characters. */
    public static CSVParser open(Path file) throws IOException {
        Reader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)));
        return FORMAT.parse(reader);
    }

    public static boolean isBlank(CSVRecord record) {
        for (String value : record) {
            if (!value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** Result of the up-front file check done before an upload is accepted. */
    public record Inspection(int dataRows) {
    }

    /**
     * Validates the whole file before it is queued: encoding, CSV syntax, header, at least one data row.
     * Also counts data rows so the progress bar has a total from the start.
     */
    public static Inspection inspect(Path file) throws InvalidFileException {
        int dataRows = 0;
        int rowNumber = 0;
        try (CSVParser parser = open(file)) {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext()) {
                throw new InvalidFileException("File is empty.");
            }
            rowNumber = 1;
            CSVRecord header = records.next();
            if (isBlank(header)) {
                throw new InvalidFileException("First row must be the header: " + HeaderMapping.expectedHeader());
            }
            HeaderMapping.from(header);
            while (records.hasNext()) {
                rowNumber++;
                if (!isBlank(records.next())) {
                    dataRows++;
                }
            }
        } catch (InvalidFileException e) {
            throw e;
        } catch (CharacterCodingException e) {
            throw new InvalidFileException("File is not valid UTF-8 text. Save it as 'CSV UTF-8' and try again.");
        } catch (UncheckedIOException | IOException | IllegalStateException e) {
            Throwable cause = e instanceof UncheckedIOException u ? u.getCause() : e;
            if (cause instanceof CharacterCodingException) {
                throw new InvalidFileException("File is not valid UTF-8 text. Save it as 'CSV UTF-8' and try again.");
            }
            throw new InvalidFileException("File is not valid CSV near row " + (rowNumber + 1) + ": "
                    + cause.getMessage());
        }
        if (dataRows == 0) {
            throw new InvalidFileException("File has a header but no data rows.");
        }
        return new Inspection(dataRows);
    }
}
