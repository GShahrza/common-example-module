package io.github.gshahrza.batch;

import io.github.gshahrza.batch.Records.Transaction;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * Simulates the database going away in the middle of a job, once. Used to show restart:
 * the failed execution is restarted and continues after the last committed chunk.
 */
@Component
public class CrashSwitch {

    private final AtomicLong crashAtId = new AtomicLong(-1);

    public void arm(long id) {
        crashAtId.set(id);
    }

    ItemWriter<Transaction> wrap(ItemWriter<Transaction> delegate) {
        return chunk -> {
            long at = crashAtId.get();
            if (at > 0 && contains(chunk, at) && crashAtId.compareAndSet(at, -1)) {
                throw new DataAccessResourceFailureException("Database connection lost while writing id " + at);
            }
            delegate.write(chunk);
        };
    }

    private static boolean contains(Chunk<? extends Transaction> chunk, long id) {
        return chunk.getItems().stream().anyMatch(t -> t.id() >= id);
    }
}
