package io.github.gshahrza.streaming.grpc.server;

import io.grpc.stub.ServerCallStreamObserver;

/**
 * gRPC flow control for server streaming. {@code onNext} never blocks: if the client reads slower
 * than we produce, messages pile up in memory. Waiting for {@code isReady()} before each message
 * makes the producer follow the client's pace (HTTP/2 flow-control window).
 */
final class FlowControl {

    private final ServerCallStreamObserver<?> observer;
    private final Object lock = new Object();

    FlowControl(ServerCallStreamObserver<?> observer) {
        this.observer = observer;
        // Called by gRPC whenever the transport can accept more data again
        observer.setOnReadyHandler(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        });
    }

    /** Blocks the producer thread until the client can take more, or the call is cancelled. */
    boolean awaitReady() throws InterruptedException {
        synchronized (lock) {
            while (!observer.isReady() && !observer.isCancelled()) {
                lock.wait(100);
            }
        }
        return !observer.isCancelled();
    }
}
