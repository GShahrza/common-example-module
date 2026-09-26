package io.github.gshahrza.streaming.webflux.compare;

import java.util.List;

public record LoadResult(
        String mode,
        int requests,
        long delayMs,
        long totalMs,
        int distinctHandlerThreads,
        List<String> handlerThreads) {
}
