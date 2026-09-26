package io.github.gshahrza.streaming.webflux.compare;

/** Which thread started handling the request and which one produced the response. */
public record CallResult(String mode, String handlerThread, String completionThread) {
}
