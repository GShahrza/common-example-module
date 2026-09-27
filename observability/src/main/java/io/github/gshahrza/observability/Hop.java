package io.github.gshahrza.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/** What one service saw: used to show that all services share one trace id. */
public record Hop(String service, String traceId, String spanId, long millis, String result) {

    public static Hop of(Tracer tracer, String service, long startNanos, String result) {
        Span span = tracer.currentSpan();
        return new Hop(service, span == null ? null : span.context().traceId(),
                span == null ? null : span.context().spanId(),
                (System.nanoTime() - startNanos) / 1_000_000, result);
    }
}
