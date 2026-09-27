# 19. Spring Batch: böyük faylı etibarlı emal etmək

[← Sonsöz: Hansını nə vaxt seçməli?](18-secim.md) · [Mündəricat](README.md) · Növbəti: [20. Kafka →](20-kafka.md)

**Hissə IV: Əlavə mövzular** · **Kod:** [`ImportJobConfig`](../spring-batch/src/main/java/io/github/gshahrza/batch/ImportJobConfig.java), [`TransactionProcessor`](../spring-batch/src/main/java/io/github/gshahrza/batch/TransactionProcessor.java), [`RejectedRowListener`](../spring-batch/src/main/java/io/github/gshahrza/batch/RejectedRowListener.java), [`JobService`](../spring-batch/src/main/java/io/github/gshahrza/batch/JobService.java) · **Demo:** http://localhost:8085 (`./gradlew :spring-batch:bootRun`)

---

## Həyatdan analogiya

Bankın gecə növbəsi. Masada 200 000 ödəniş qəbzi var, səhərə qədər hamısı sistemə daxil edilməlidir. Təcrübəli operator belə işləyir:

- Qəbzləri **500-lük dəstələrlə** götürür. Bir dəstəni yoxlayır, daxil edir, qutuya qoyur və vərəqə yazır: "240-cı dəstə bitdi".
- Cırıq və ya yanlış doldurulmuş qəbzi atmır: **ayrıca qovluğa** qoyur və üstünə səbəbini yazır. Səhər kimsə onlara baxacaq.
- Məbləği sıfır olan qəbz səhv deyil, sadəcə mənasızdır: onu **kənara** qoyur.
- Qovluqda 100-dən çox səhv qəbz yığılsa, işi dayandırıb rəhbərə zəng edir: deməli, qəbzlər yox, bütün paket səhvdir.
- Gecə yarısı işıq sönür. İşıq gələndə **əvvəldən başlamır**: vərəqəyə baxır, 241-ci dəstədən davam edir.
- Eyni paketi ikinci dəfə gətirsələr, "bu artıq daxil edilib" deyir.
- Sonda hər hesab üzrə yekunu çıxarır.

Spring Batch bu operatorun iş qaydasıdır. Dəstə **chunk**, vərəqə **JobRepository**, səhv qəbzlər qovluğu **skip listener**, sıfır məbləğli qəbzləri kənara qoymaq **filter**, rəhbərə zəng **skip limit**, "işıq gələndə davam et" isə **restart**-dır.

## Problem

Bankın gündəlik çıxarışı gəlir: 200 000 sətirlik CSV. İlk ağla gələn kod belədir:

```java
for (String line : Files.readAllLines(file)) {        // 1
    Transaction t = parse(line);                        // 2
    repository.save(t);                                 // 3
}
```

Kiçik faylda işləyir, real həyatda isə hər sətir problem yaradır:

1. **Yaddaş.** Fayl bütünlüklə yaddaşa yüklənir. 2 GB-lıq fayl tətbiqi yıxır.
2. **Bir səhv sətir.** `parse` 57 000-ci sətirdə exception atır və bütün iş dayanır. Səhər 199 999 düzgün sətir də emal olunmamış qalır.
3. **Hər sətir ayrıca `INSERT` və commit.** Yavaşdır. Hamısını bir tranzaksiyaya salmaq isə uzun lock və nəhəng rollback deməkdir.
4. **Çökmə.** 150 000-ci sətirdə deploy oldu. Yenidən başlasaq, ilk 150 000 sətir **ikinci dəfə** yazılır; başlamasaq, 50 000 sətir itir.
5. **Görünməzlik.** "Neçə sətir oxundu? Neçəsi atıldı, niyə? Nə vaxt bitəcək?" Heç kim bilmir.
6. **Təkrar.** Cron səhvən işi iki dəfə başladı və pul iki dəfə yazıldı.

Bu problemlərin hamısı hər batch işində təkrarlanır. Spring Batch onları bir dəfə, düzgün həll edib.

## Əsas anlayışlar

```
Job  "importTransactionsJob"                          ← nə edilir (kod)
 └─ JobInstance   Job + identifying parametrlər       ← bu faylın importu (məntiqi iş)
     ├─ JobExecution #1  FAILED                       ← hər cəhd
     │   └─ StepExecution "import"   read=120 488, write=118 691, commit=240
     └─ JobExecution #2  COMPLETED                    ← restart
         ├─ StepExecution "import"   read=79 992 (yalnız qalanı)
         └─ StepExecution "summary"
```

| Anlayış | Nədir |
|---|---|
| **Job** | Bir və ya bir neçə step-dən ibarət iş |
| **Step** | Müstəqil mərhələ: ya chunk-oriented (oxu → emal et → yaz), ya da tasklet (bir əməliyyat) |
| **JobInstance** | Job + onun **identifying** parametrləri. "5 may tarixli çıxarışın importu" |
| **JobExecution** | JobInstance-ı işə salmaq cəhdi. Bir instance-ın bir neçə execution-u ola bilər (restart) |
| **StepExecution** | Step-in bir icrası, bütün sayğaclarla birlikdə |
| **ExecutionContext** | Step-in "vərəqəsi": reader-in mövqeyi və s. Hər commit-də yadda saxlanılır |
| **JobRepository** | Bütün bunların saxlandığı yer: bazadakı `BATCH_*` cədvəlləri |

Sonuncu ən vacibidir. Restart, monitorinq və "bu fayl artıq import olunub" yoxlaması `JobRepository` sayəsində işləyir. Spring Boot 4-də bunun üçün `spring-boot-starter-batch-jdbc` lazımdır; adi `spring-boot-starter-batch` state-i yalnız yaddaşda saxlayır və restart etmək olmur.

## Həll, addım-addım

### 1. Job: iki step

```java
@Bean
Job importTransactionsJob(JobRepository jobRepository, Step importStep, Step summaryStep) {
    return new JobBuilder("importTransactionsJob", jobRepository)
            .start(importStep)      // CSV → bank_transaction
            .next(summaryStep)      // bank_transaction → account_summary
            .build();
}
```

İkinci step yalnız birinci uğurla bitəndə işləyir. Step-lər arasında şərtli keçidlər də qurmaq olur (`.on("FAILED").to(...)`), amma çox vaxt ardıcıl zəncir kifayətdir.

### 2. Chunk: oxu, emal et, yaz, commit

```java
return new StepBuilder("import", jobRepository)
        .<RawTransaction, Transaction>chunk(500)   // 500 sətir = bir tranzaksiya
        .transactionManager(transactionManager)
        .reader(reader)
        .processor(processor)
        .writer(writer)
        .stream(reader)                            // reader-in mövqeyi hər commit-də saxlanılır
        ...
```

Dövr belədir:

```
┌─ tranzaksiya ───────────────────────────────────────────────────────┐
│ read × 500 → process × 500 → write(500-lük siyahı) → COMMIT         │
│                                  + ExecutionContext: "read.count=500"│
└─────────────────────────────────────────────────────────────────────┘
┌─ tranzaksiya ───────────────────────────────────────────────────────┐
│ read × 500 → ... → COMMIT, "read.count=1000"                         │
└─────────────────────────────────────────────────────────────────────┘
...
```

Açar məqam budur: məlumat və "vərəqə" (**reader-in mövqeyi**) **eyni tranzaksiyada** commit olunur. Ya hər ikisi yazılır, ya heç biri. Restart-ın etibarlı olmasını məhz bu təmin edir.

Chunk ölçüsü balans məsələsidir:

| Kiçik chunk (10) | Böyük chunk (10 000) |
|---|---|
| Çox commit, yavaş | Az commit, sürətli |
| Xəta olanda az iş itir | Xəta olanda çox iş rollback olur |
| Qısa lock-lar | Uzun tranzaksiya, çox yaddaş |

Adətən 100-1000 arası seçilir. Modulda `batch.chunk-size: 500`-dür.

### 3. Reader: faylı sətir-sətir, yaddaşa yükləmədən

```java
@Bean
@StepScope
FlatFileItemReader<RawTransaction> reader(@Value("#{jobParameters['input.file']}") String file) {
    DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
    tokenizer.setNames("id", "account", "amount", "currency", "date");   // sütun sayı səhvdirsə: parse xətası
    return new FlatFileItemReaderBuilder<RawTransaction>()
            .name("transactionReader")               // ExecutionContext-dəki açarın adı
            .resource(new FileSystemResource(file))
            .linesToSkip(1)                          // başlıq sətri
            .lineMapper((line, lineNumber) -> { ... new RawTransaction(lineNumber, ...) })
            .build();
}
```

Üç detal:

- **Hər şey `String` kimi oxunur.** Reader-in işi faylı oxumaqdır, qərar verməkdir yox. Məbləğin `12.5x` olması processor-un problemidir. Beləliklə, səhv sətir reader-i dayandırmır.
- **Sətir nömrəsi** (`lineNumber`) saxlanılır. Rədd edilən sətir haqqında "57 312-ci sətir: yanlış valyuta" demək "haradasa səhv var" deməkdən qat-qat faydalıdır.
- **`@StepScope`.** Reader singleton olsaydı, bütün job-lar eyni obyekti (eyni fayl, eyni mövqe) paylaşardı. `@StepScope` hər step icrası üçün yeni bean yaradır və `jobParameters['input.file']`-i **həmin anda** inject edir (late binding).

### 4. Processor: üç cavab

```java
public Transaction process(RawTransaction raw) {
    if (!IBAN.matcher(raw.account()).matches()) {
        throw new InvalidTransactionException("Invalid account: " + raw.account());   // skip
    }
    ...
    if (amount.signum() == 0) {
        return null;                                                                 // filter
    }
    return new Transaction(..., amount.multiply(rate).setScale(2, RoundingMode.HALF_UP), date);  // yaz
}
```

| Processor nə edir | Nəticə | Sayğac |
|---|---|---|
| Obyekt qaytarır | Writer-ə gedir | `write` |
| `null` qaytarır | Səssizcə buraxılır: səhv deyil | `filter` |
| Exception atır | Skip edilir (icazə varsa) və ya step dayanır | `processSkip` |

`null` ilə exception arasındakı fərq biznes fərqidir. Sıfır məbləğli sətir faylda ola bilər, onu yazmırıq, vəssalam. Yanlış IBAN isə **problemdir**: kimsə ona baxmalıdır.

### 5. Writer: 500 sətir, bir şəbəkə gedişi

```java
new JdbcBatchItemWriterBuilder<Transaction>()
        .dataSource(dataSource)
        .sql("INSERT INTO bank_transaction (file_id, id, account, ...) VALUES (:fileId, :id, :account, ...)")
        .itemSqlParameterSourceProvider(SimplePropertySqlParameterSource::new)
        .build();
```

Writer tək sətir yox, bütün chunk-ı alır və onu bir **JDBC batch** kimi göndərir. Bu sandbox-da 200 000 sətir təxminən 4 saniyədə yazıldı. Hər sətri ayrıca `INSERT` və commit ilə yazmaq on dəfələrlə yavaş olardı.

Cədvəlin primary key-i `(file_id, id)`-dir. Bu, sadəcə indeks deyil, **zəmanətdir**: hər hansı səbəbdən eyni sətir iki dəfə yazılmağa cəhd edilsə, baza bunu qəbul etməyəcək.

### 6. Skip: bir səhv sətir bütün faylı öldürməsin

```java
.faultTolerant()
.skip(InvalidTransactionException.class, FlatFileParseException.class)
.skipLimit(100)
.skipListener(rejectedRowListener)
```

- **Nə skip edilir:** yalnız **sətir səviyyəli, gözlənilən** xətalar. Biznes qaydası pozulub (`InvalidTransactionException`) və ya sətir oxunmur (`FlatFileParseException`, məsələn sütun çatışmır).
- **Nə skip edilmir:** baza xətası, yaddaş çatışmazlığı, bug. Onlar sətirdə deyil, sistemdədir: skip etsək, bütün sonrakı sətirlər də "səhv" olacaq.
- **Skip limit** qoruyucudur. 200 000 sətirdən 30 000-i səhvdirsə, problem sətirlərdə deyil: format dəyişib, və ya səhv fayl göndərilib. Belə faylı "uğurla, amma yarısını" import etmək təhlükəlidir, dayandırmaq düzgündür.
- **Heç nə səssizcə itmir.** `RejectedRowListener` hər atılan sətri səbəbi ilə `rejected_transaction` cədvəlinə yazır:

```java
public void onSkipInProcess(RawTransaction item, Throwable t) {
    save(item.line() + ": " + item, t.getMessage());     // "57312: 57312,AZ00,100.00,AZN,...  Invalid account: AZ00"
}
public void onSkipInRead(Throwable t) { ... }           // sətir heç oxuna bilmədi (FlatFileParseException)
```

Səhər biznes bu siyahıya baxır, səhvləri düzəldir və yalnız onları yenidən göndərir.

Testdə bu belə görünür. 1000 sətirlik faylda hər 50-ci sətir səhvdir (20 sətir), hər 97-ci sətrin məbləği sıfırdır (10 sətir). Nəticə: `readSkip=4`, `processSkip=16`, `filter=10`, `write=970`.

### 7. Restart: işıq gələndə qaldığın yerdən davam et

Modulda `CrashSwitch` writer-i bükür və verilən sətrə çatanda **bir dəfə** "baza əlaqəsi itdi" xətası atır. Demo-nun default ssenarisi: 200 000 sətir, çökmə 120 000-ci sətirdə.

**Birinci cəhd:**

```
chunk 1..240   → commit (sətir 1 – 120 000), vərəqə: "read.count ≈ 120 000"
chunk 241      → read, process ✓ → write ✗ DataAccessResourceFailureException
               → ROLLBACK (yalnız bu chunk), step FAILED, job FAILED
```

`StepExecution`: `read=120 488`, `commit=240`, status `FAILED`. Bazada 240 chunk-ın sətirləri qalır; 241-ci chunk-dan heç nə yazılmayıb.

**Restart:**

```java
jobOperator.restart(failedExecution);
```

- **Eyni JobInstance** üçün yeni `JobExecution` yaranır.
- Spring Batch "import" step-inin son `ExecutionContext`-ini oxuyur: reader 120 000-ci sətrə qədər irəliləmişdi.
- Reader faylı açır və ilk 120 000 sətri **emal etmədən** keçir, 120 001-ci sətirdən davam edir.
- Nəticə: `read=79 992`, `commit=160`, `COMPLETED`. Ardınca `summary` step-i işləyir. O, ilk cəhddə heç başlamamışdı.

Yekunda bazada 197 820 sətir var: 200 000 − 100 rədd edilən − 2 080 sıfır məbləğli. **Heç bir sətir iki dəfə yazılmayıb**, bunu primary key zəmanət edir.

Restart-ın işləməsi üçün üç şərt var:

1. **Reader state saxlamalıdır** (`ItemStream`). Spring Batch-in hazır reader-ləri saxlayır. Öz reader-inizi yazırsınızsa, `open/update` metodlarında mövqeyi `ExecutionContext`-ə yazın, yoxsa restart faylı əvvəldən oxuyacaq.
2. **JobRepository bazada olmalıdır**, yaddaşda yox.
3. **Tamamlanmış step-lər təkrarlanmır.** Buna görə hər step ya restart-a hazır olmalıdır (chunk), ya da idempotent (tasklet, 9-cu addım).

### 8. JobInstance: eyni faylı iki dəfə import etmək olmaz

```java
new JobParametersBuilder()
        .addString("input.file", file.toString())    // identifying: JobInstance-ı müəyyən edir
        .toJobParameters();
```

Eyni fayl eyni `JobInstance` deməkdir. Instance artıq `COMPLETED`-dirsə, yenidən başlatmaq `JobInstanceAlreadyCompleteException` verir (testdə yoxlanılır).

Bu, "cron səhvən iki dəfə işlədi" probleminin həllidir. Həqiqətən yenidən import lazımdırsa, yeni identifying parametr əlavə olunur (məs. `run.id`). Bu, şüurlu qərar olur, təsadüf olmur.

### 9. Tasklet: bir əməliyyatlıq step

Hər step chunk olmalı deyil. Hesablar üzrə yekun bir SQL-dir:

```java
.tasklet((contribution, context) -> {
    jdbc.sql("DELETE FROM account_summary WHERE file_id = ?").param(fileId).update();
    jdbc.sql("""
            INSERT INTO account_summary (file_id, account, tx_count, total_azn)
            SELECT file_id, account, COUNT(*), SUM(amount_azn) FROM bank_transaction
            WHERE file_id = ? GROUP BY file_id, account""").param(fileId).update();
    return RepeatStatus.FINISHED;
}, transactionManager)
```

`DELETE` + `INSERT ... SELECT` step-i **idempotent** edir: iki dəfə işləsə də nəticə eynidir. Restart edilə bilən job-da tasklet-lər belə yazılmalıdır, çünki tasklet ortada dayansa, restart onu yenidən başdan icra edəcək.

## Job-u işə salmaq

Spring Boot default olaraq bütün job-ları **tətbiq başlayanda** işə salır. Web tətbiqdə bunu söndürün:

```yaml
spring.batch.job.enabled: false
```

Modulda job REST ilə başladılır (`POST /api/jobs/import`) və arxa fonda işləyir. Sorğu `202 Accepted` qaytarır, gedişatı isə `GET /api/jobs/executions` göstərir. Sayğaclar hər commit-də `BATCH_STEP_EXECUTION`-a yazılır; demo səhifə onları saniyədə bir neçə dəfə oxuyub canlı göstərir.

Production-da job-u adətən bunlar başladır:

- **Cədvəl:** `@Scheduled(cron = ...)`, Kubernetes `CronJob`, Quartz. Kubernetes-də çox vaxt job ayrıca, qısaömürlü pod kimi işləyir: başlayır, işi görür, çıxır.
- **Hadisə:** fayl S3-ə və ya SFTP-yə düşdü, Kafka mesajı gəldi.
- **İnsan:** admin paneldən "yenidən başlat" düyməsi (demo-dakı kimi).

Bir neçə instance-da eyni job-un eyni anda başlamaması üçün də JobRepository cavabdehdir: eyni JobInstance-ın ikinci execution-u `JobExecutionAlreadyRunningException` alır.

## Monitorinq: hər şey bazadadır

JobRepository cədvəlləri özü monitorinq alətidir:

```sql
SELECT e.JOB_EXECUTION_ID, e.STATUS, s.STEP_NAME, s.READ_COUNT, s.WRITE_COUNT, s.FILTER_COUNT,
       s.READ_SKIP_COUNT + s.PROCESS_SKIP_COUNT AS SKIPPED, s.COMMIT_COUNT, s.START_TIME, s.END_TIME
FROM BATCH_JOB_EXECUTION e JOIN BATCH_STEP_EXECUTION s ON s.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
ORDER BY e.JOB_EXECUTION_ID DESC;
```

Bir tələ: uğursuzluğun səbəbi (`EXIT_MESSAGE`) stack trace-dir və **2500 simvolla kəsilir**. Əsl səbəb ("Caused by: ...") çox vaxt kəsilən hissədə qalır. Modulda `FailureReasonListener` qısa səbəbi (`DataAccessResourceFailureException: Database connection lost...`, `SkipLimitExceededException: ...`) step-in `ExecutionContext`-inə ayrıca yazır, demo səhifə də onu göstərir.

## Daha böyük həcm

200 000 sətir bir thread-də saniyələr çəkir. Milyonlarla sətir və ya ağır emal üçün Spring Batch miqyaslanmanın bir neçə səviyyəsini təklif edir:

| Üsul | Necə | Nə vaxt |
|---|---|---|
| **Multi-threaded step** | `.taskExecutor(...)`: chunk-lar paralel emal olunur | Sadə, amma reader thread-safe olmalıdır və restart çətinləşir (sıra itir) |
| **Partitioning** | Məlumat hissələrə bölünür (məs. id aralıqları, fayllar), hər hissə ayrı step icrasıdır | Ən çox istifadə olunan; hər hissə öz restart-ı ilə |
| **Remote chunking / partitioning** | Hissələr başqa node-lara (Kafka, RabbitMQ ilə) göndərilir | Bir maşın kifayət etmədikdə |

Əvvəl ölçün. Çox vaxt darboğaz batch deyil, bazadakı indeks, writer-in batch ölçüsü və ya processor-dakı xarici çağırışdır.

## Spring Batch 6 (Spring Boot 4) ilə nə dəyişdi

- Paketlər dəyişib: `Job`, `JobExecution` → `org.springframework.batch.core.job`; reader və writer-lər → `org.springframework.batch.infrastructure.item`.
- `JobLauncher` və `JobExplorer` əvəzinə `JobOperator` və `JobRepository` istifadə olunur.
- `chunk(size, transactionManager)` əvəzinə `chunk(size).transactionManager(tm)`: yeni `ChunkOrientedStep`.
- Retry artıq Spring Retry-dan deyil, Spring Framework 7-nin öz `core.retry` paketindən gəlir.
- JDBC JobRepository ayrıca starter-dir: `spring-boot-starter-batch-jdbc`.

İnternetdəki nümunələrin çoxu Spring Batch 4-5 üçündür; importlar tutmursa, səbəb budur.

## Tələlər

- **Reader state saxlamır.** Restart faylı əvvəldən oxuyur və dublikatlar yaranır. Öz reader-inizdə `ItemStream`-i unutmayın.
- **Writer idempotent deyil.** Unikal açarsız `INSERT` və ya "balansı artır" kimi əməliyyatlar restart-da ikiqat effekt verə bilər. Unikal açar, `MERGE/UPSERT` və ya idempotency açarı istifadə edin.
- **Hər şeyi skip etmək.** `skip(Exception.class)` bug-ları və baza xətalarını da "səhv sətir"ə çevirir, job isə "uğurla" bitir. Skip siyahısı dar olmalıdır.
- **Processor-da xarici API.** Hər sətir üçün HTTP çağırışı 200 000 × 100 ms = 5.5 saat edir. Məlumatı əvvəlcədən yükləyin (keş) və ya partitioning edin.
- **`@StepScope`-suz reader.** Paralel və ya ardıcıl job-lar eyni reader obyektini paylaşır.
- **Böyük chunk + uzun emal.** Tranzaksiya dəqiqələrlə açıq qalır, lock-lar digər sorğuları gözlədir.
- **`spring.batch.job.enabled`.** Söndürülməsə, hər deploy-da bütün job-lar işə düşür.

## Yadda saxla

- Batch işinin əsas problemləri yaddaş, səhv sətirlər, çökmə, təkrar və görünməzlikdir. Spring Batch bunların hamısını standart şəkildə həll edir.
- **Chunk** = oxu, emal et, yaz, **commit**. Məlumat və reader-in mövqeyi eyni tranzaksiyada saxlanılır: restart-ın açarı budur.
- Processor-un üç cavabı var: obyekt (yaz), `null` (filter), exception (skip və ya dayan).
- Yalnız sətir səviyyəli xətaları skip edin, səbəbini saxlayın və **skip limit** qoyun.
- **Identifying parametr** eyni işin iki dəfə tamamlanmasının qarşısını alır.
- Tasklet-lər və writer-lər idempotent olmalıdır.
- JobRepository həm restart mexanizmi, həm də monitorinq mənbəyidir.

## Tapşırıqlar

1. Demo-da default dəyərlərlə job başladın (200 000 sətir, çökmə 120 000-də). Execution cədvəlində `commit` sayına baxın: 120 000 / 500-ə bərabərdirmi? Sonra **Restart** basın və ikinci execution-un `read` sayını birinci ilə toplayın.
2. "Hər N-ci sətir səhv" dəyərini `10` edin. Job hansı səbəblə dayanır? Nəticə panelində neçə sətrin bazaya yazıldığına baxın: skip limit aşılana qədər commit olunan chunk-lar geri qaytarılırmı?
3. `batch.chunk-size`-ı `10` və `5000` edib eyni faylı import edin. Müddəti və `commit` sayını müqayisə edin.
4. `TransactionProcessor`-a qayda əlavə edin: 1 000 000 AZN-dən böyük əməliyyat rədd edilsin. Testdə yeni sayğacları yoxlayın.
5. (Çətin) `summaryStep`-dən `DELETE`-i silin. Tasklet eyni fayl üçün ikinci dəfə işləsə (məsələn, `INSERT`-dən sonra, commit-dən əvvəl çökdü və restart olundu), nə baş verər? İpucu: `account_summary`-nin primary key-i. Bəs primary key olmasaydı?

---

[← Sonsöz: Hansını nə vaxt seçməli?](18-secim.md) · [Mündəricat](README.md) · Növbəti: [20. Kafka →](20-kafka.md)
