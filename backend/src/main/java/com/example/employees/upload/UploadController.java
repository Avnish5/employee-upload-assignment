package com.example.employees.upload;

import com.example.employees.web.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/uploads")
@Validated
public class UploadController {

    private final UploadService uploadService;
    private final UploadRepository uploads;
    private final ErrorReportWriter errorReport;

    public UploadController(UploadService uploadService, UploadRepository uploads, ErrorReportWriter errorReport) {
        this.uploadService = uploadService;
        this.uploads = uploads;
        this.errorReport = errorReport;
    }

    /** Accepts the file, runs file-level checks, queues it and returns 202 immediately. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadView> upload(@RequestPart("file") MultipartFile file) {
        UploadView view = uploadService.submit(file);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/uploads/" + view.id()))
                .body(view);
    }

    /** Status and live progress counters; the UI polls this every second. */
    @GetMapping("/{id}")
    public UploadView get(@PathVariable long id) {
        return find(id);
    }

    /** Upload history, newest first. */
    @GetMapping
    public List<UploadView> recent(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return uploads.findRecent(limit);
    }

    @GetMapping(value = "/{id}/errors.csv", produces = "text/csv")
    public void errors(@PathVariable long id, HttpServletResponse response) throws IOException {
        UploadView upload = find(id);
        if (!upload.status().isTerminal()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_READY",
                    "Upload " + id + " is still " + upload.status() + "; the report is available when it finishes.");
        }
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"upload-" + id + "-errors.csv\"");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Writer writer = response.getWriter();
        errorReport.write(id, writer);
        writer.flush();
    }

    private UploadView find(long id) {
        return uploads.findById(id).orElseThrow(() -> ApiException.notFound("Upload " + id + " not found."));
    }
}
