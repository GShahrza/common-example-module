package io.github.gshahrza.batch;

import io.github.gshahrza.batch.Records.InvalidTransactionException;
import io.github.gshahrza.batch.Records.RawTransaction;
import io.github.gshahrza.batch.Records.Transaction;
import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.infrastructure.item.file.transform.FieldSet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.SimplePropertySqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * importTransactionsJob:
 *   step 1 "import"  (chunk-oriented): CSV → validate/convert → bank_transaction table
 *   step 2 "summary" (tasklet):        bank_transaction → account_summary
 */
@Configuration
class ImportJobConfig {

    static final String JOB_NAME = "importTransactionsJob";
    static final String INPUT_FILE = "input.file";

    @Bean
    Job importTransactionsJob(JobRepository jobRepository, Step importStep, Step summaryStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(importStep)
                .next(summaryStep)
                .build();
    }

    @Bean
    Step importStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                    FlatFileItemReader<RawTransaction> reader, TransactionProcessor processor,
                    JdbcBatchItemWriter<Transaction> writer, RejectedRowListener rejectedRowListener,
                    CrashSwitch crashSwitch,
                    @Value("${batch.chunk-size}") int chunkSize, @Value("${batch.skip-limit}") long skipLimit) {
        return new StepBuilder("import", jobRepository)
                .<RawTransaction, Transaction>chunk(chunkSize)   // read N, process N, write N, commit
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(crashSwitch.wrap(writer))
                .stream(reader)                                   // the reader's position is saved on every commit
                .faultTolerant()
                .skip(InvalidTransactionException.class, FlatFileParseException.class)
                .skipLimit(skipLimit)                             // more bad rows than this: the file itself is broken
                .skipListener(rejectedRowListener)
                .listener(new FailureReasonListener())
                .build();
    }

    @Bean
    Step summaryStep(JobRepository jobRepository, PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        return new StepBuilder("summary", jobRepository)
                .tasklet((contribution, context) -> {
                    String fileId = fileId(String.valueOf(context.getStepContext().getJobParameters().get(INPUT_FILE)));
                    // Delete + insert: running the step again gives the same result (idempotent)
                    jdbc.sql("DELETE FROM account_summary WHERE file_id = ?").param(fileId).update();
                    int accounts = jdbc.sql("""
                            INSERT INTO account_summary (file_id, account, tx_count, total_azn)
                            SELECT file_id, account, COUNT(*), SUM(amount_azn)
                            FROM bank_transaction WHERE file_id = ? GROUP BY file_id, account
                            """).param(fileId).update();
                    contribution.incrementWriteCount(accounts);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    /**
     * {@code @StepScope}: a new reader for every step execution, created with that execution's job
     * parameters (late binding). Without it, one singleton reader would be shared by all runs.
     */
    @Bean
    @StepScope
    FlatFileItemReader<RawTransaction> reader(@Value("#{jobParameters['input.file']}") String file) {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("id", "account", "amount", "currency", "date");   // strict: wrong column count = parse error
        return new FlatFileItemReaderBuilder<RawTransaction>()
                .name("transactionReader")
                .resource(new FileSystemResource(file))
                .linesToSkip(1)
                .lineMapper((line, lineNumber) -> {
                    FieldSet f = tokenizer.tokenize(line);
                    return new RawTransaction(lineNumber, f.readString("id"), f.readString("account"),
                            f.readString("amount"), f.readString("currency"), f.readString("date"));
                })
                .build();
    }

    @Bean
    @StepScope
    TransactionProcessor processor(@Value("#{jobParameters['input.file']}") String file) {
        return new TransactionProcessor(fileId(file));
    }

    @Bean
    @StepScope
    RejectedRowListener rejectedRowListener(JdbcClient jdbc, @Value("#{jobParameters['input.file']}") String file) {
        return new RejectedRowListener(jdbc, fileId(file));
    }

    /** JDBC batch insert: one round trip per chunk instead of one per row. */
    @Bean
    JdbcBatchItemWriter<Transaction> writer(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<Transaction>()
                .dataSource(dataSource)
                .sql("""
                        INSERT INTO bank_transaction (file_id, id, account, amount, currency, amount_azn, booked_on)
                        VALUES (:fileId, :id, :account, :amount, :currency, :amountAzn, :bookedOn)
                        """)
                .itemSqlParameterSourceProvider(SimplePropertySqlParameterSource::new)
                .build();
    }

    static String fileId(String path) {
        return new FileSystemResource(path).getFilename();
    }
}
