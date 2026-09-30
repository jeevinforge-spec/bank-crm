package com.bank.crm.controller;

import com.bank.crm.dto.BulkErrorResponse;
import com.bank.crm.dto.JobResponse;
import com.bank.crm.service.bulk.BulkLoadProperties;
import com.bank.crm.service.bulk.BulkLoadService;
import com.bank.crm.service.bulk.SampleDataGenerator;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bulk")
public class BulkLoadController {

    private final BulkLoadService bulkLoadService;
    private final SampleDataGenerator generator;
    private final BulkLoadProperties props;

    public BulkLoadController(BulkLoadService bulkLoadService, SampleDataGenerator generator, BulkLoadProperties props) {
        this.bulkLoadService = bulkLoadService;
        this.generator = generator;
        this.props = props;
    }

    /** Upload a CSV; returns 202 with the job id immediately while loading continues in the background. */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse upload(@RequestParam("file") MultipartFile file,
                              @RequestParam(required = false) Integer threads,
                              @RequestParam(required = false) Integer chunkSize) throws IOException {
        return bulkLoadService.submitUpload(file, threads, chunkSize);
    }

    /** Generate N synthetic customers server-side and load them through the same pipeline. */
    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse generate(@RequestParam(defaultValue = "10000") int rows,
                                @RequestParam(defaultValue = "0") int invalidPercent,
                                @RequestParam(required = false) Integer threads,
                                @RequestParam(required = false) Integer chunkSize) throws IOException {
        return bulkLoadService.submitGenerated(rows, invalidPercent, threads, chunkSize);
    }

    /** Download a generated CSV to inspect or edit before uploading. */
    @GetMapping("/sample")
    public void sample(@RequestParam(defaultValue = "10000") int rows,
                       @RequestParam(defaultValue = "0") int invalidPercent,
                       HttpServletResponse response) throws IOException {
        if (rows < 1 || rows > props.maxGeneratedRows())
            throw new IllegalArgumentException("rows must be between 1 and " + props.maxGeneratedRows());
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"customers-" + rows + ".csv\"");
        generator.write(response.getWriter(), rows, Math.max(0, Math.min(invalidPercent, 50)));
    }

    @GetMapping("/jobs")
    public List<JobResponse> jobs(@RequestParam(defaultValue = "20") int limit) {
        return bulkLoadService.recentJobs(limit);
    }

    @GetMapping("/jobs/{jobId}")
    public JobResponse job(@PathVariable String jobId) {
        return bulkLoadService.getJob(jobId);
    }

    @GetMapping("/jobs/{jobId}/errors")
    public List<BulkErrorResponse> errors(@PathVariable String jobId, @RequestParam(defaultValue = "200") int limit) {
        return bulkLoadService.errors(jobId, limit);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "defaultThreads", props.defaultThreads(),
                "maxThreads", props.maxThreads(),
                "defaultChunkSize", props.defaultChunkSize(),
                "maxChunkSize", props.maxChunkSize(),
                "maxGeneratedRows", props.maxGeneratedRows(),
                "availableProcessors", Runtime.getRuntime().availableProcessors());
    }
}
