package io.github.gshahrza.batch;

import io.github.gshahrza.batch.Records.InvalidTransactionException;
import io.github.gshahrza.batch.Records.RawTransaction;
import io.github.gshahrza.batch.Records.Transaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Validates and converts one row. Three possible outcomes:
 * return an item (write it), return null (filter it out, not an error),
 * throw (skip it as invalid, or fail the job if the error is not skippable).
 */
class TransactionProcessor implements ItemProcessor<RawTransaction, Transaction> {

    /** Azerbaijani IBAN: AZ + 2 check digits + 4 letters bank code + 20 characters. */
    private static final Pattern IBAN = Pattern.compile("AZ\\d{2}[A-Z]{4}[A-Z0-9]{20}");
    static final Map<String, BigDecimal> RATES_TO_AZN = Map.of(
            "AZN", BigDecimal.ONE,
            "USD", new BigDecimal("1.70"),
            "EUR", new BigDecimal("1.85"),
            "GBP", new BigDecimal("2.15"));

    private final String fileId;

    TransactionProcessor(String fileId) {
        this.fileId = fileId;
    }

    @Override
    public Transaction process(RawTransaction raw) {
        if (!IBAN.matcher(raw.account()).matches()) {
            throw new InvalidTransactionException("Invalid account: " + raw.account());
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw.amount());
        } catch (NumberFormatException e) {
            throw new InvalidTransactionException("Invalid amount: " + raw.amount());
        }
        BigDecimal rate = RATES_TO_AZN.get(raw.currency());
        if (rate == null) {
            throw new InvalidTransactionException("Unknown currency: " + raw.currency());
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw.date());
        } catch (DateTimeParseException e) {
            throw new InvalidTransactionException("Invalid date: " + raw.date());
        }
        if (amount.signum() == 0) {
            return null;   // zero-amount rows are noise: filtered, not rejected
        }
        return new Transaction(fileId, Long.parseLong(raw.id()), raw.account(), amount, raw.currency(),
                amount.multiply(rate).setScale(2, RoundingMode.HALF_UP), date);
    }
}
