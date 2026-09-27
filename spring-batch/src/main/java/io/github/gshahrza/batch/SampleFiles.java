package io.github.gshahrza.batch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Generates a bank statement CSV with a controlled number of broken rows. */
@Component
public class SampleFiles {

    static final List<String> ACCOUNTS = List.of(
            "AZ21NABZ00000000137010001944", "AZ77AIIB38060019441100202111", "AZ96PAHA40060AZNHCCA0UNL0012",
            "AZ53IBAZ38010019449100136222", "AZ08UBAZ03823010042410016888", "AZ15KAPT00000000000000555777",
            "AZ42RZBA38090019440000456321", "AZ60VTBA38000019440000009911");
    private static final List<String> CURRENCIES = List.of("AZN", "AZN", "USD", "EUR", "GBP");
    /** One kind of mistake per bad row, in turn. */
    private static final List<String> BAD_ROWS = List.of(
            "%d,AZ00,100.00,AZN,2026-05-01",                              // invalid account
            "%d,AZ21NABZ00000000137010001944,12.5x,AZN,2026-05-01",       // invalid amount
            "%d,AZ21NABZ00000000137010001944,40.00,XYZ,2026-05-01",       // unknown currency
            "%d,AZ21NABZ00000000137010001944,40.00,USD,2026-13-45",       // invalid date
            "%d,AZ21NABZ00000000137010001944,40.00");                     // missing columns

    private final Path workDir;
    private final AtomicLong sequence = new AtomicLong();

    SampleFiles(@Value("${batch.work-dir}") Path workDir) {
        this.workDir = workDir;
    }

    /**
     * @param rows     data rows (without the header)
     * @param badEvery every n-th row is broken (0 = none)
     */
    public Path generate(int rows, int badEvery) {
        try {
            Files.createDirectories(workDir);
            Path file = workDir.resolve("transactions-%d-%d.csv".formatted(System.currentTimeMillis(), sequence.incrementAndGet()));
            try (BufferedWriter out = Files.newBufferedWriter(file)) {
                out.write("id,account,amount,currency,date\n");
                LocalDate start = LocalDate.of(2026, 1, 1);
                int bad = 0;
                for (int i = 1; i <= rows; i++) {
                    if (badEvery > 0 && i % badEvery == 0) {
                        out.write(BAD_ROWS.get(bad++ % BAD_ROWS.size()).formatted(i));
                    } else {
                        // every 97th row has amount 0: filtered by the processor, not an error
                        String amount = i % 97 == 0 ? "0.00" : "%d.%02d".formatted((i * 37) % 5000 - 1000, i % 100);
                        out.write("%d,%s,%s,%s,%s".formatted(i, ACCOUNTS.get(i % ACCOUNTS.size()), amount,
                                CURRENCIES.get(i % CURRENCIES.size()), start.plusDays(i % 180)));
                    }
                    out.write('\n');
                }
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
