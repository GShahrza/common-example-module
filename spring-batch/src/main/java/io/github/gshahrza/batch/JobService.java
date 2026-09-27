package io.github.gshahrza.batch;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Service;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    public record StepView(String name, String status, long read, long write, long filter,
                           long readSkip, long processSkip, long commits, long rollbacks, Long millis,
                           String failureReason) {
    }

    public record ExecutionView(long id, long instanceId, String file, String status, String exitCode,
                                String exitDescription, LocalDateTime start, LocalDateTime end,
                                boolean restartable, List<StepView> steps) {
    }

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job job;
    private final SampleFiles sampleFiles;
    private final CrashSwitch crashSwitch;

    JobService(JobOperator jobOperator, JobRepository jobRepository, Job importTransactionsJob,
               SampleFiles sampleFiles, CrashSwitch crashSwitch) {
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.job = importTransactionsJob;
        this.sampleFiles = sampleFiles;
        this.crashSwitch = crashSwitch;
    }

    /** Generates a file and starts the job for it in the background. Returns the file id. */
    public String startImport(int rows, int badEvery, long crashAtId) {
        Path file = sampleFiles.generate(rows, badEvery);
        crashSwitch.arm(crashAtId);
        JobParameters parameters = new JobParametersBuilder()
                // identifying parameter: the same file = the same JobInstance, it cannot complete twice
                .addString(ImportJobConfig.INPUT_FILE, file.toString())
                .toJobParameters();
        background(() -> jobOperator.start(job, parameters));
        return ImportJobConfig.fileId(file.toString());
    }

    /** Restart = a new JobExecution of the same JobInstance; it continues where the failed one stopped. */
    public void restart(long executionId) {
        JobExecution failed = jobRepository.getJobExecution(executionId);
        if (failed == null) {
            throw new IllegalArgumentException("No job execution " + executionId);
        }
        background(() -> jobOperator.restart(failed));
    }

    public List<ExecutionView> recentExecutions() {
        return jobRepository.getJobInstances(ImportJobConfig.JOB_NAME, 0, 10).stream()
                .flatMap(instance -> jobRepository.getJobExecutions(instance).stream())
                .sorted(Comparator.comparing(JobExecution::getId).reversed())
                .map(this::view)
                .toList();
    }

    private ExecutionView view(JobExecution e) {
        List<StepView> steps = e.getStepExecutions().stream()
                .sorted(Comparator.comparing(StepExecution::getId))
                .map(s -> new StepView(s.getStepName(), s.getStatus().name(), s.getReadCount(), s.getWriteCount(),
                        s.getFilterCount(), s.getReadSkipCount(), s.getProcessSkipCount(), s.getCommitCount(),
                        s.getRollbackCount(), s.getStartTime() == null ? null
                                : Duration.between(s.getStartTime(), s.getEndTime() == null ? LocalDateTime.now() : s.getEndTime()).toMillis(),
                        FailureReasonListener.reason(s)))
                .toList();
        boolean restartable = e.getStatus().name().equals("FAILED") || e.getStatus().name().equals("STOPPED");
        String file = String.valueOf(e.getJobParameters().getString(ImportJobConfig.INPUT_FILE));
        return new ExecutionView(e.getId(), e.getJobInstance().getInstanceId(), ImportJobConfig.fileId(file),
                e.getStatus().name(), e.getExitStatus().getExitCode(), failureReason(steps),
                e.getStartTime(), e.getEndTime(), restartable, steps);
    }

    private static String failureReason(List<StepView> steps) {
        return steps.stream().map(StepView::failureReason).filter(d -> d != null).findFirst().orElse(null);
    }

    private interface JobCall {
        void run() throws Exception;
    }

    private static void background(JobCall call) {
        Thread.ofVirtual().name("job-launcher").start(() -> {
            try {
                call.run();
            } catch (Exception e) {
                log.warn("Job could not be started: {}", e.toString());
            }
        });
    }
}
