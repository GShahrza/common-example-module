package io.github.gshahrza.streaming.mvc.job;

import io.github.gshahrza.streaming.mvc.config.Sleeper;
import io.github.gshahrza.streaming.mvc.config.StreamingProperties;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;

/** Simulates a long-running background job (e.g. a report or an import) that records its progress. */
@Service
public class JobService {

    static final int STEPS = 20;

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final StreamingProperties properties;

    public JobService(ExecutorService streamingExecutor, StreamingProperties properties) {
        this.executor = streamingExecutor;
        this.properties = properties;
    }

    public Job start() {
        Job job = new Job(UUID.randomUUID().toString());
        jobs.put(job.getId(), job);
        executor.execute(() -> {
            for (int step = 1; step <= STEPS; step++) {
                if (!Sleeper.sleep(properties.jobStepDelay())) {
                    return;
                }
                job.add(new ProgressEvent(step, step * 100 / STEPS, "Processed batch " + step + " of " + STEPS));
            }
            job.finish();
        });
        return job;
    }

    public Optional<Job> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }
}
