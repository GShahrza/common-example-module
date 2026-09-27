package io.github.gshahrza.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Jobs are started synchronously here, so every assertion sees the finished execution. */
@SpringBootTest(properties = {"batch.chunk-size=100", "batch.skip-limit=50"})
class SpringBatchTest {

    @Autowired
    JobOperator jobOperator;
    @Autowired
    Job importTransactionsJob;
    @Autowired
    SampleFiles sampleFiles;
    @Autowired
    CrashSwitch crashSwitch;
    @Autowired
    JdbcClient jdbc;

    JobParameters params(Path file) {
        return new JobParametersBuilder().addString(ImportJobConfig.INPUT_FILE, file.toString()).toJobParameters();
    }

    long count(String table, Path file) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE file_id = ?")
                .param(file.getFileName().toString()).query(Long.class).single();
    }

    StepExecution importStep(JobExecution execution) {
        return execution.getStepExecutions().stream().filter(s -> s.getStepName().equals("import")).findFirst().orElseThrow();
    }

    @Test
    void invalidRowsAreSkippedAndZeroRowsFiltered() throws Exception {
        Path file = sampleFiles.generate(1000, 50);   // 20 broken rows, 10 zero-amount rows (every 97th)

        JobExecution execution = jobOperator.start(importTransactionsJob, params(file));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution step = importStep(execution);
        assertThat(step.getReadSkipCount()).isEqualTo(4);        // 4 rows with missing columns
        assertThat(step.getProcessSkipCount()).isEqualTo(16);    // 16 rows breaking business rules
        assertThat(step.getFilterCount()).isEqualTo(10);
        assertThat(step.getWriteCount()).isEqualTo(970);
        assertThat(count("bank_transaction", file)).isEqualTo(970);
        assertThat(count("rejected_transaction", file)).isEqualTo(20);
        assertThat(count("account_summary", file)).isEqualTo(SampleFiles.ACCOUNTS.size());
    }

    @Test
    void failedJobIsRestartedFromTheLastCommittedChunk() throws Exception {
        Path file = sampleFiles.generate(1000, 0);
        crashSwitch.arm(550);   // the chunk with rows 501..600 fails

        JobExecution first = jobOperator.start(importTransactionsJob, params(file));

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(FailureReasonListener.reason(importStep(first)))
                .startsWith("DataAccessResourceFailureException: Database connection lost");
        assertThat(count("bank_transaction", file)).isEqualTo(495);   // 5 chunks committed (500 rows, 5 filtered)

        JobExecution second = jobOperator.restart(first);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId()).isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(importStep(second).getReadCount()).isEqualTo(500);  // only the rest of the file was read
        assertThat(count("bank_transaction", file)).isEqualTo(990);    // the primary key guarantees no duplicates
    }

    @Test
    void aCompletedFileCannotBeImportedTwice() throws Exception {
        Path file = sampleFiles.generate(10, 0);
        jobOperator.start(importTransactionsJob, params(file));

        assertThatThrownBy(() -> jobOperator.start(importTransactionsJob, params(file)))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void tooManyBadRowsFailTheJob() throws Exception {
        Path file = sampleFiles.generate(1000, 5);   // 200 bad rows > skip limit 50

        JobExecution execution = jobOperator.start(importTransactionsJob, params(file));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        StepExecution step = importStep(execution);
        assertThat(step.getSkipCount()).isBetween(1L, 50L);   // stopped at the limit, did not read the whole file
        assertThat(step.getReadCount()).isLessThan(1000);
        assertThat(FailureReasonListener.reason(step)).startsWith("SkipLimitExceededException");
    }
}
