package io.github.gshahrza.batch;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.FatalStepExecutionException;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.core.retry.RetryException;

/**
 * By default the exit description is a stack trace cut at 2500 characters, which usually ends
 * before the interesting "Caused by". This saves only the reason (the first cause below the
 * framework's wrapper exceptions) into the step's execution context, which is persisted too.
 */
class FailureReasonListener implements StepExecutionListener {

    static final String KEY = "failureReason";

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        stepExecution.getFailureExceptions().stream().findFirst()
                .map(FailureReasonListener::unwrap)
                .ifPresent(e -> stepExecution.getExecutionContext()
                        .putString(KEY, e.getClass().getSimpleName() + ": " + e.getMessage()));
        return null;   // keep the exit status as it is
    }

    /** Skips the wrappers the framework adds: FatalStepExecutionException, RetryException. */
    private static Throwable unwrap(Throwable e) {
        while (e.getCause() != null
                && (e instanceof FatalStepExecutionException || e instanceof RetryException)) {
            e = e.getCause();
        }
        return e;
    }

    static String reason(StepExecution stepExecution) {
        return stepExecution.getExecutionContext().containsKey(KEY)
                ? stepExecution.getExecutionContext().getString(KEY) : null;
    }
}
