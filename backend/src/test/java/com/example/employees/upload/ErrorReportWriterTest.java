package com.example.employees.upload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorReportWriterTest {

    @Test
    void neutralisesSpreadsheetFormulas() {
        assertThat(ErrorReportWriter.safe("=HYPERLINK(\"http://x\")")).isEqualTo("'=HYPERLINK(\"http://x\")");
        assertThat(ErrorReportWriter.safe("+91 9876543210")).isEqualTo("'+91 9876543210");
        assertThat(ErrorReportWriter.safe("-5")).isEqualTo("'-5");
        assertThat(ErrorReportWriter.safe("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    @Test
    void leavesOrdinaryValuesUntouched() {
        assertThat(ErrorReportWriter.safe("Fatima")).isEqualTo("Fatima");
        assertThat(ErrorReportWriter.safe("17/06/1992")).isEqualTo("17/06/1992");
        assertThat(ErrorReportWriter.safe("")).isEmpty();
        assertThat(ErrorReportWriter.safe(null)).isNull();
    }
}
