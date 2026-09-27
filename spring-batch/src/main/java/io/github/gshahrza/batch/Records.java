package io.github.gshahrza.batch;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Items flowing through the step: a raw CSV line in, a validated transaction out. */
final class Records {

    /** Everything is a String: the reader must not fail on bad data, the processor decides. */
    record RawTransaction(long line, String id, String account, String amount, String currency, String date) {
        @Override
        public String toString() {
            return String.join(",", id, account, amount, currency, date);
        }
    }

    record Transaction(String fileId, long id, String account, BigDecimal amount, String currency,
                       BigDecimal amountAzn, LocalDate bookedOn) {
    }

    /** A business rule was violated: skip the row, keep the job running. */
    static class InvalidTransactionException extends RuntimeException {
        InvalidTransactionException(String message) {
            super(message);
        }
    }

    private Records() {
    }
}
