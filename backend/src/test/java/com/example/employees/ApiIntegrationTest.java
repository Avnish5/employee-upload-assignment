package com.example.employees;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level tests: async upload through the real worker, report download, file-level rejection, list. */
@AutoConfigureMockMvc
class ApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void reset() throws Exception {
        resetToPreloadedState();
    }

    @Test
    void uploadIsProcessedAsynchronouslyAndReportUsesInputRowNumbers() throws Exception {
        byte[] content = Files.readAllBytes(DATA.resolve("upload-1.csv"));
        MvcResult accepted = mvc.perform(multipart("/api/uploads")
                        .file(new MockMultipartFile("file", "upload-1.csv", "text/csv", content)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.totalRows").value(500))
                .andReturn();
        long id = json.readTree(accepted.getResponse().getContentAsString()).get("id").asLong();

        JsonNode done = awaitTerminal(id);
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("processedRows").asInt()).isEqualTo(500);
        assertThat(done.get("insertedRows").asInt()).isEqualTo(480);
        assertThat(done.get("skippedRows").asInt()).isEqualTo(5);
        assertThat(done.get("rejectedRows").asInt()).isEqualTo(15);

        MvcResult report = mvc.perform(get("/api/uploads/{id}/errors.csv", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"upload-" + id + "-errors.csv\""))
                .andReturn();
        String csv = report.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿row_number,error_codes,error_messages,first_name,last_name,dob,");

        List<CSVRecord> records = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(new StringReader(csv.substring(1))).getRecords();
        assertThat(records).extracting(r -> r.get("row_number")).containsExactly(
                "101", "109", "116", "193", "208", "215", "295", "353", "369", "376", "426", "429", "430", "469", "499");

        CSVRecord badDob = records.get(2);
        assertThat(badDob.get("error_messages")).isEqualTo("dob must be in DD-MM-YYYY format (got '17/06/1992')");
        assertThat(badDob.get("first_name")).isEqualTo("Sunil");
        assertThat(badDob.get("dob")).isEqualTo("17/06/1992"); // original value, not normalized

        CSVRecord shortRow = records.get(10);
        assertThat(shortRow.get("error_codes")).isEqualTo("COLUMN_COUNT");
        assertThat(shortRow.get("error_messages")).startsWith("Expected 9 fields but found 8");
    }

    @Test
    void fileLevelProblemsAreRejectedBeforeProcessing() throws Exception {
        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file", "employees.xlsx",
                        "application/octet-stream", new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_FILE"));

        mvc.perform(multipart("/api/uploads").file(csv("first_name,last_name,dob\nA,B,01-01-1990\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith(
                        "Header is missing required column(s): gender, mobile, email, department")));

        mvc.perform(multipart("/api/uploads").file(csv(
                        "first_name,last_name,dob,gender,mobile,email,department,designation,date_of_joining\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("File has a header but no data rows."));

        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file", "latin1.csv", "text/csv",
                        "first_name,last_name,dob,gender,mobile,email,department,designation,date_of_joining\nJosé"
                                .getBytes(StandardCharsets.ISO_8859_1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("File is not valid UTF-8")));

        assertThat(employeeCount()).isEqualTo(50);
    }

    @Test
    void reorderedColumnsAndBlankLinesKeepSpreadsheetRowNumbers() throws Exception {
        String file = "mobile,first_name,last_name,dob,gender,email,department,designation,date_of_joining,notes\n"
                + "9000000001,Asha,Menon,01-01-1990,f,,Revenue,Inspector,01-01-2020,ignored\n"
                + "\n"
                + "9000000002,Ravi,Menon,01-01-1990,M,,Revenue,Inspector,99-01-2020,x\n";
        MvcResult accepted = mvc.perform(multipart("/api/uploads").file(csv(file)))
                .andExpect(status().isAccepted()).andReturn();
        long id = json.readTree(accepted.getResponse().getContentAsString()).get("id").asLong();
        JsonNode done = awaitTerminal(id);
        assertThat(done.get("insertedRows").asInt()).isEqualTo(1);
        assertThat(done.get("rejectedRows").asInt()).isEqualTo(1);

        String csv = mvc.perform(get("/api/uploads/{id}/errors.csv", id)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\r\n4,INVALID_DATE,"); // row 3 is the blank line

        mvc.perform(get("/api/employees").param("q", "asha"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].gender").value("F"))
                .andExpect(jsonPath("$.items[0].dob").value("01-01-1990"))
                .andExpect(jsonPath("$.items[0].mobile").value("9000000001"));
    }

    @Test
    void employeeListIsPagedAndSorted() throws Exception {
        mvc.perform(get("/api/employees").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(50))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].lastName").value("Abubakar"));
        mvc.perform(get("/api/employees").param("department", "Town Planning"))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/employees").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/uploads/999999")).andExpect(status().isNotFound());
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "test.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode awaitTerminal(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode node = json.readTree(mvc.perform(get("/api/uploads/{id}", id))
                    .andReturn().getResponse().getContentAsString());
            String s = node.get("status").asText();
            if (s.equals("COMPLETED") || s.equals("FAILED")) {
                return node;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Upload " + id + " did not finish in time");
    }
}
