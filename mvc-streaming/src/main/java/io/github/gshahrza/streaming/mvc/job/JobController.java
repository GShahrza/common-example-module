package io.github.gshahrza.streaming.mvc.job;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Example 2: progress of a long-running job over SSE, with resume after reconnect.
 *
 * <p>Every event carries an {@code id}. When the connection drops, the browser's EventSource reconnects
 * automatically and sends the last id it saw in the {@code Last-Event-ID} header, so the server continues
 * from there instead of starting over.
 */
@RestController
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final JobService jobService;
    private final ExecutorService executor;

    public JobController(JobService jobService, ExecutorService streamingExecutor) {
        this.jobService = jobService;
        this.executor = streamingExecutor;
    }

    @PostMapping("/api/jobs")
    public ResponseEntity<Map<String, String>> start() {
        Job job = jobService.start();
        String events = "/api/jobs/" + job.getId() + "/events";
        return ResponseEntity.accepted()
                .location(URI.create(events))
                .body(Map.of("id", job.getId(), "events", events));
    }

    @GetMapping(path = "/api/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id,
                             @RequestHeader(name = "Last-Event-ID", required = false) Integer lastEventIdHeader,
                             @RequestParam(name = "lastEventId", required = false) Integer lastEventIdParam) {
        // The header is sent by EventSource on automatic reconnects; the query parameter is for manual
        // reconnects, because a new EventSource cannot set headers
        int lastEventId = lastEventIdHeader != null ? lastEventIdHeader
                : lastEventIdParam != null ? lastEventIdParam : 0;
        // Validate before opening the stream, so an unknown id is a normal 404 response
        Job job = jobService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job " + id));

        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(10).toMillis());
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(e -> open.set(false));

        executor.execute(() -> {
            try {
                // Tell EventSource to wait 1s before reconnecting
                emitter.send(SseEmitter.event().reconnectTime(1000).comment("connected, resuming after " + lastEventId));
                int sent = lastEventId;
                long lastWrite = System.nanoTime();
                while (open.get()) {
                    for (ProgressEvent event : job.eventsAfter(sent)) {
                        emitter.send(SseEmitter.event()
                                .id(String.valueOf(event.step()))
                                .name("progress")
                                .data(event, MediaType.APPLICATION_JSON));
                        sent = event.step();
                        lastWrite = System.nanoTime();
                    }
                    if (job.isFinished() && job.eventsAfter(sent).isEmpty()) {
                        emitter.send(SseEmitter.event().name("completed").data(Map.of("id", id), MediaType.APPLICATION_JSON));
                        emitter.complete();
                        return;
                    }
                    // Comments are ignored by EventSource but keep proxies/load balancers from closing an idle connection
                    if (System.nanoTime() - lastWrite > HEARTBEAT_INTERVAL.toNanos()) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        lastWrite = System.nanoTime();
                    }
                    Thread.sleep(POLL_INTERVAL);
                }
            } catch (IOException e) {
                log.debug("Job {} client disconnected: {}", id, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
