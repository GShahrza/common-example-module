# spring-batch: böyük faylların etibarlı emalı

Spring Batch 6 ilə klassik ssenari: bankın gündəlik çıxarışını (yüz minlərlə sətirlik CSV) bazaya import etmək. Bu, sadə `for` dövrü deyil, çünki real həyatda:

- faylın içində **səhv sətirlər** olur və bir səhv sətir görə bütün faylı rədd etmək olmaz;
- proses yarıda **çökə bilər** (baza, şəbəkə, deploy), və 2 saatlıq işi sıfırdan başlamaq olmaz;
- **eyni faylı iki dəfə** import etmək (ikiqat pul) qadağandır;
- nə baş verdiyi **izlənməlidir**: neçə sətir oxundu, yazıldı, atlandı, niyə.

| Mövzu | Harada |
|---|---|
| Chunk-oriented step (read → process → write → commit) | `ImportJobConfig.importStep` |
| Skip (səhv sətri atla, səbəbini yaz) və skip limit | `.faultTolerant().skip(...)`, `RejectedRowListener` |
| Filter (sətri səssizcə buraxmaq) | `TransactionProcessor` → `null` |
| Restart (qaldığı yerdən davam) | `FlatFileItemReader` state + `JobOperator.restart` |
| JobInstance: eyni fayl iki dəfə tamamlanmır | identifying job parameter `input.file` |
| Tasklet step və idempotent yekun | `ImportJobConfig.summaryStep` |
| `@StepScope` və late binding | `reader(...)`, `processor(...)` |

📖 Kitab üslubunda izah (analogiya, addım-addım həll, tapşırıqlar): [19. Spring Batch: böyük faylı etibarlı emal etmək](../docs/19-spring-batch.md).

## İşə salma qaydası

Heç bir xarici servis lazım deyil: baza H2-dir (yaddaşda) və tətbiqlə birlikdə qalxır.

```bash
./gradlew :spring-batch:bootRun
```

və ya Docker ilə:

```bash
docker build -f spring-batch/Dockerfile -t spring-batch .
docker run --rm -p 8085:8085 spring-batch
```

Sonra brauzerdə **http://localhost:8085** açın.

Demo ssenarisi (default dəyərlərlə):

1. **Başlat**: 200 000 sətirlik fayl yaradılır, job işləyir. 120 000-ci sətirdə "baza əlaqəsi itir" və job `FAILED` olur.
2. Execution cədvəlində neçə sətrin artıq commit olunduğunu görürsünüz.
3. **Restart**: yeni execution yalnız qalan ~80 000 sətri oxuyur və `COMPLETED` olur. Bazada dublikat yoxdur (primary key buna zəmanət verir).
4. Execution-a klikləyin: hesablar üzrə yekun və rədd edilən sətirlər səbəbləri ilə birlikdə görünür.
5. "Hər N-ci sətir səhv" dəyərini `10` edin: 100-dən çox səhv sətir olduğu üçün job `SkipLimitExceededException` ilə dayanır. Bu fayl zədəlidir və insan baxmalıdır.

Terminaldan:

```bash
curl -X POST localhost:8085/api/jobs/import -H 'Content-Type: application/json' \
     -d '{"rows":200000,"badEvery":2000,"crashAtId":120000}'
curl localhost:8085/api/jobs/executions                    # status və sayğaclar
curl -X POST localhost:8085/api/jobs/executions/1/restart
curl localhost:8085/api/files/<fileId>                     # nəticə
```

---

## Necə işləyir

### Əsas anlayışlar

```
Job  (importTransactionsJob)
 └─ JobInstance   = Job + identifying parametrlər (input.file=/tmp/.../transactions-1.csv)
     ├─ JobExecution #1   FAILED     ← hər cəhd ayrıca execution
     │   └─ StepExecution "import"   read=120 488, write=118 691, commit=240, ...
     └─ JobExecution #2   COMPLETED  ← restart
         ├─ StepExecution "import"   read=79 992 (yalnız qalanı)
         └─ StepExecution "summary"
```

Bütün bu məlumat **JobRepository**-də, yəni bazadakı `BATCH_*` cədvəllərində saxlanılır. Spring Batch onları özü yaradır (`spring-boot-starter-batch-jdbc`). Restart, monitorinq və "bu fayl artıq import olunub" yoxlaması həmin cədvəllərdən işləyir. Demo səhifədəki sayğaclar da oradan oxunur.

### Chunk-oriented step

```java
new StepBuilder("import", jobRepository)
        .<RawTransaction, Transaction>chunk(500)
        .reader(reader)          // FlatFileItemReader: CSV-ni sətir-sətir oxuyur
        .processor(processor)    // validasiya + AZN-ə çevirmə
        .writer(writer)          // JdbcBatchItemWriter: 500 sətir = 1 JDBC batch
```

Dövr belədir: 500 sətir oxu, 500-nü emal et, hamısını bir dəfəyə yaz, **commit** et, sonra növbəti 500. Hər commit-də reader-in mövqeyi ("neçənci sətirdəyəm") da `BATCH_STEP_EXECUTION_CONTEXT`-ə yazılır. Bu, restart-ın açarıdır.

Chunk ölçüsü balans məsələsidir:

- **Kiçik chunk:** çox commit, yavaş.
- **Böyük chunk:** uzun tranzaksiya, çox yaddaş, və xəta olanda çox iş geri qaytarılır.

Adətən 100-1000 arası seçilir (`batch.chunk-size`).

### Processor-un üç cavabı

| Processor | Nəticə | Sayğac | Nümunə |
|---|---|---|---|
| obyekt qaytarır | yazılır | `write` | düzgün sətir |
| `null` qaytarır | səssizcə buraxılır | `filter` | məbləğ 0.00 |
| exception atır | skip edilir **və ya** job dayanır | `processSkip` | yanlış IBAN, naməlum valyuta |

Reader də xəta verə bilər: sütun sayı səhv olanda `FlatFileParseException` yaranır və `readSkip` artır.

### Skip və skip limit

```java
.faultTolerant()
.skip(InvalidTransactionException.class, FlatFileParseException.class)
.skipLimit(100)
.skipListener(rejectedRowListener)
```

- Yalnız **gözlənilən, sətir səviyyəli** xətalar skip olunur. Baza xətası skip olunmur: o, bütün sonrakı sətirləri də pozar.
- `RejectedRowListener` hər atlanan sətri səbəbi ilə birlikdə `rejected_transaction` cədvəlinə yazır. Heç nə səssizcə itmir; biznes sonra bu siyahıya baxıb düzəldə bilər.
- **Skip limit** qoruyucudur. 100 000 sətirdən 30 000-i səhvdirsə, problem sətirlərdə deyil, faylın özündədir (format dəyişib, yanlış fayl göndərilib). O zaman job-u dayandırmaq düzgündür.

Skip olunan sətrə görə chunk-ın qalanı itmir: Spring Batch səhv elementi çıxarıb chunk-ı yenidən emal edir.

### Restart

`CrashSwitch` writer-i bükür və verilən sətrə çatanda bir dəfə `DataAccessResourceFailureException` atır, yəni baza əlaqəsinin itməsini simulyasiya edir. Nə baş verir:

1. Həmin chunk-ın tranzaksiyası rollback olunur; əvvəlki 240 chunk artıq commit olunub.
2. Step və job `FAILED` olur. Reader-in son commit olunmuş mövqeyi bazada qalır.
3. `jobOperator.restart(execution)` **eyni JobInstance** üçün yeni execution yaradır. Reader saxlanmış mövqedən davam edir, əvvəlki sətirləri yenidən oxumur.
4. `summary` step-i ilk dəfə işləyir; tamamlanmış step-lər restart-da təkrarlanmır.

Restart-ın işləməsi üçün reader **state saxlamalıdır** (`ItemStream`). Spring Batch-in hazır reader-ləri bunu edir; öz reader-inizi yazanda `open/update` metodlarını unutmayın.

### Eyni faylı iki dəfə import etmək

`input.file` **identifying** parametrdir. Eyni fayl eyni `JobInstance` deməkdir. Instance `COMPLETED` olubsa, yenidən başlatmaq `JobInstanceAlreadyCompleteException` verir. Yenidən import etmək həqiqətən lazımdırsa, fərqli identifying parametr (məs. `run.id`) əlavə olunur.

### `@StepScope`

```java
@Bean
@StepScope
FlatFileItemReader<RawTransaction> reader(@Value("#{jobParameters['input.file']}") String file)
```

Reader singleton olsaydı, bütün job-lar eyni obyektdən (eyni fayl, eyni mövqe) istifadə edərdi. `@StepScope` hər step execution üçün yeni bean yaradır və job parametrlərini həmin anda inject edir (late binding).

### Tasklet step

`summary` step-i chunk deyil, bir SQL əməliyyatıdır: hesablar üzrə yekunu `account_summary`-yə yazır. O, **idempotentdir** (`DELETE` + `INSERT ... SELECT`): iki dəfə işləsə də nəticə eynidir. Restart edilə bilən job-larda hər step belə olmalıdır.

### Performans

Bu sandbox-da 200 000 sətir təxminən 4 saniyədə import olundu. Sürəti bunlar verir:

- `JdbcBatchItemWriter`: 500 insert bir şəbəkə gedişində;
- hər chunk-a bir commit;
- streaming reader: fayl yaddaşa tam yüklənmir.

Daha böyük həcmlər üçün:

- `taskExecutor(...)` ilə multi-threaded step;
- partitioning (fayl hissələrə bölünür, hər hissəni ayrı thread və ya node emal edir);
- remote chunking.

## Testlər

```bash
./gradlew :spring-batch:test
```

| Test | Nəyi yoxlayır |
|---|---|
| `invalidRowsAreSkippedAndZeroRowsFiltered` | 1000 sətir: 4 read skip + 16 process skip + 10 filter = 970 yazıldı; rədd səbəbləri saxlanıb |
| `failedJobIsRestartedFromTheLastCommittedChunk` | 550-ci sətirdə çökmə → 495 sətir bazada; restart yalnız 500 sətir oxuyur; cəmi 990, dublikat yoxdur |
| `aCompletedFileCannotBeImportedTwice` | `JobInstanceAlreadyCompleteException` |
| `tooManyBadRowsFailTheJob` | skip limit aşıldı → `FAILED`, səbəb `SkipLimitExceededException` |

## Spring Batch 6 (Boot 4) ilə gələn dəyişikliklər

- Paketlər dəyişib: `Job`, `JobExecution`, `JobParameters` → `org.springframework.batch.core.job.*`; reader/writer-lər → `org.springframework.batch.infrastructure.item.*`.
- `JobLauncher` və `JobExplorer` əvəzinə `JobOperator` və `JobRepository` istifadə olunur.
- `chunk(size, transactionManager)` əvəzinə `chunk(size).transactionManager(tm)`: yeni `ChunkOrientedStep`.
- Retry artıq Spring Retry-dan deyil, Spring Framework 7-nin `org.springframework.core.retry` paketindən gəlir.
- JDBC job repository ayrıca starter-dir: `spring-boot-starter-batch-jdbc`. Adi `spring-boot-starter-batch` state-i bazada saxlamır, ona görə restart üçün uyğun deyil.

## Tələlər

- **`spring.batch.job.enabled`.** Default olaraq Boot bütün job-ları start-da işə salır. Web tətbiqdə bunu `false` edin.
- **Reader state-siz olanda** restart faylı əvvəldən oxuyur və dublikat yaranır.
- **Writer idempotent deyilsə** (məs. `INSERT` unikal açarsız), restart-da yarımçıq chunk-dan dublikat yarana bilər. Ya unikal açar qoyun, ya da `MERGE/UPSERT` istifadə edin.
- **Exit description 2500 simvolla kəsilir** və əsl səbəb çox vaxt görünmür. `FailureReasonListener` qısa səbəbi execution context-ə yazır.
