package io.github.gshahrza.streaming.mvc.job;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Job {

    private final String id;
    private final List<ProgressEvent> events = new CopyOnWriteArrayList<>();
    private volatile boolean finished;

    Job(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    void add(ProgressEvent event) {
        events.add(event);
    }

    void finish() {
        finished = true;
    }

    public boolean isFinished() {
        return finished;
    }

    /** Events with a step greater than {@code afterStep}; steps start at 1. */
    public List<ProgressEvent> eventsAfter(int afterStep) {
        return events.stream().filter(e -> e.step() > afterStep).toList();
    }
}
