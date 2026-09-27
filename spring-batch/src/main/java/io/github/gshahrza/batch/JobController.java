package io.github.gshahrza.batch;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JobController {

    /** crashAtId > 0: the writer fails once when it reaches this row id. */
    record ImportRequest(Integer rows, Integer badEvery, Long crashAtId) {
    }

    record Summary(String account, int txCount, BigDecimal totalAzn) {
    }

    record Rejected(String line, String reason) {
    }

    record Result(String fileId, long imported, long rejected, List<Summary> accounts, List<Rejected> rejectedSample) {
    }

    private final JobService jobs;
    private final JdbcClient jdbc;

    JobController(JobService jobs, JdbcClient jdbc) {
        this.jobs = jobs;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/jobs/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, String> start(@RequestBody ImportRequest request) {
        int rows = Math.clamp(request.rows() == null ? 100_000 : request.rows(), 1, 2_000_000);
        int badEvery = request.badEvery() == null ? 2000 : Math.max(0, request.badEvery());
        long crashAt = request.crashAtId() == null ? -1 : request.crashAtId();
        return Map.of("fileId", jobs.startImport(rows, badEvery, crashAt));
    }

    @PostMapping("/api/jobs/executions/{id}/restart")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void restart(@PathVariable long id) {
        jobs.restart(id);
    }

    @GetMapping("/api/jobs/executions")
    List<JobService.ExecutionView> executions() {
        return jobs.recentExecutions();
    }

    @GetMapping("/api/files/{fileId}")
    Result result(@PathVariable String fileId) {
        long imported = jdbc.sql("SELECT COUNT(*) FROM bank_transaction WHERE file_id = ?")
                .param(fileId).query(Long.class).single();
        long rejected = jdbc.sql("SELECT COUNT(*) FROM rejected_transaction WHERE file_id = ?")
                .param(fileId).query(Long.class).single();
        List<Summary> accounts = jdbc.sql("""
                        SELECT account, tx_count, total_azn FROM account_summary
                        WHERE file_id = ? ORDER BY total_azn DESC""")
                .param(fileId).query(Summary.class).list();
        List<Rejected> sample = jdbc.sql("SELECT line, reason FROM rejected_transaction WHERE file_id = ? LIMIT 10")
                .param(fileId).query(Rejected.class).list();
        return new Result(fileId, imported, rejected, accounts, sample);
    }
}
