package io.github.gshahrza.streaming.mvc.job;

/** One step of a job. {@code step} doubles as the SSE event id used for resuming. */
public record ProgressEvent(int step, int percent, String message) {
}
