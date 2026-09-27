package io.github.gshahrza.batch;

import io.github.gshahrza.batch.Records.RawTransaction;
import io.github.gshahrza.batch.Records.Transaction;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Every skipped row is saved with its reason, so nothing disappears silently. */
class RejectedRowListener implements SkipListener<RawTransaction, Transaction> {

    private final JdbcClient jdbc;
    private final String fileId;

    RejectedRowListener(JdbcClient jdbc, String fileId) {
        this.jdbc = jdbc;
        this.fileId = fileId;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        String line = t instanceof FlatFileParseException p ? p.getLineNumber() + ": " + p.getInput() : null;
        save(line, "Unreadable line: " + rootMessage(t));
    }

    @Override
    public void onSkipInProcess(RawTransaction item, Throwable t) {
        save(item.line() + ": " + item, t.getMessage());
    }

    private void save(String line, String reason) {
        jdbc.sql("INSERT INTO rejected_transaction (file_id, line, reason) VALUES (?, ?, ?)")
                .params(fileId, line, reason)
                .update();
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
    }
}
