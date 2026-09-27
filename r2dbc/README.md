# r2dbc: WebFlux ilə bazaya bloklamadan (R2DBC + PostgreSQL)

**R2DBC** (Reactive Relational Database Connectivity) relyasiyalı bazalar üçün JDBC-nin reaktiv alternatividir. JDBC sorğu göndərib cavab gələnə qədər thread-i saxlayır; R2DBC isə sorğunu göndərir, thread-i azad edir və nəticə gələndə `Mono`/`Flux` davam edir. Beləliklə, WebFlux tətbiqi HTTP-dən bazaya qədər sonadək reaktiv olur.

| Mövzu | Harada |
|---|---|
| Reaktiv repository: `Mono`/`Flux` qaytaran CRUD və səhifələmə | `OrderRepository` |
| `DatabaseClient`: əl ilə SQL, join, aqreqat | `OrderService.stream`, `statsByCustomer` |
| Reaktiv tranzaksiya (`@Transactional` + `Mono`) | `OrderService.place` |
| Bazadan client-ə NDJSON axını, backpressure, cancel | `/api/orders/stream` |
| R2DBC, event loop-da JDBC və ayrı pool-da JDBC: ölçülmüş müqayisə | `compare/` |
| Flyway ilə migration (JDBC ilə) | `db/migration/V1__orders.sql` |
| Testcontainers ilə real PostgreSQL | `R2dbcTest` |

## İşə salma qaydası

Ardıcıllıqla:

### 1. PostgreSQL-i işə salın

```bash
docker run -d --name postgres -p 5432:5432 \
  -e POSTGRES_DB=shop -e POSTGRES_USER=shop -e POSTGRES_PASSWORD=shop postgres:17-alpine
```

### 2. Tətbiqi işə salın

```bash
./gradlew :r2dbc:bootRun
```

İlk start-da Flyway cədvəlləri yaradır və 100 000 sifariş əlavə edir (bir neçə saniyə).

və ya Docker ilə:

```bash
docker build -f r2dbc/Dockerfile -t r2dbc .
docker run --rm -p 8091:8091 --add-host=host.docker.internal:host-gateway r2dbc
```

### 3. Baxın

**http://localhost:8091**: üç üsulun müqayisəsi, axın və tranzaksiya. Terminaldan:

```bash
curl localhost:8091/api/orders/1
curl 'localhost:8091/api/orders?customer=aynur&page=0&size=20'
curl localhost:8091/api/stats/customers
curl -N 'localhost:8091/api/orders/stream?limit=5'                       # NDJSON
curl -X POST localhost:8091/api/orders -H 'Content-Type: application/json' \
     -d '{"customer":"aynur","productId":3,"quantity":2}'
curl 'localhost:8091/api/compare?mode=jdbc-on-event-loop&requests=100'
```

Backpressure-i öz gözünüzlə görmək üçün yavaş client:

```bash
curl -s -N --limit-rate 20k localhost:8091/api/orders/stream -o /dev/null &   # 20 KB/s oxuyur
sleep 5; curl localhost:8091/api/orders/stream/fetched                         # bazadan oxunan sətirlər artmır
```

---

## Necə işləyir

### Ölçmə: eyni sorğu üç üsulla

Sorğu `SELECT pg_sleep(0.2)`-dir, yəni baza 200 ms "düşünür". 100 paralel sorğu göndərilir. Server-in 4 event-loop thread-i var; R2DBC və JDBC pool-larının hər birində 20 bağlantı var. Bu sandbox-da ölçülən nəticələr:

| Üsul | Cəmi | Server thread-ləri | Nə baş verir |
|---|---|---|---|
| **R2DBC** | **~1.4 s** | 2 (`reactor-tcp-*`) | 100 sorğu / 20 bağlantı × 0.2 s = 1 s. Gözləyən sorğular thread tutmur. |
| **JDBC event loop-da** | **~5.3 s** | 4 (`reactor-http-*`) | Hər event-loop thread-i 200 ms bloklanır: 100 / 4 × 0.2 s = 5 s. Bu müddətdə həmin thread-lərdəki **bütün digər** sorğular da (hətta bazaya getməyənlər) gözləyir. |
| **JDBC `boundedElastic`-də** | ~1.2 s | 40 (`boundedElastic-*`) | Sürətlidir, amma hər gözləyən sorğu bir thread tutur. |

Nəticələr:

1. **WebFlux-da JDBC-ni birbaşa çağırmaq ən pis variantdır.** Bu, adi Spring MVC-dən də pisdir, çünki MVC-də ən azı 200 thread var, burada isə 4.
2. **`subscribeOn(Schedulers.boundedElastic())`** bloklayan kitabxana üçün düzgün "yamaqdır": event loop azad qalır. Amma thread sayı yenə bloklanan sorğuların sayı qədərdir (maks. `10 × nüvə`, sonra növbə).
3. **R2DBC**-də gözləmə "pulsuzdur": iki thread bütün bağlantılara xidmət edir. Məhdudiyyət artıq thread-lər yox, bazanın özü və pool ölçüsüdür, və belə də olmalıdır.

Diqqət: bu müqayisə R2DBC-nin **sorğunu sürətləndirdiyini** göstərmir; bazanın cavab vermə müddəti eynidir. R2DBC **gözləmənin qiymətini** azaldır: çoxlu eyni vaxtlı, yavaş I/O olanda.

### Repository və `DatabaseClient`

```java
public interface OrderRepository extends ReactiveCrudRepository<Order, Long> {
    Flux<Order> findByCustomerOrderByIdDesc(String customer, Pageable page);
}
```

Spring Data JPA-ya bənzəyir, amma **JPA deyil**. Lazy loading, `@OneToMany`, dirty checking və ikinci səviyyəli keş yoxdur; `Order.productId` sadəcə sütundur. Join və aqreqatlar `DatabaseClient` ilə yazılır:

```java
db.sql("SELECT customer, COUNT(*) AS orders, SUM(amount) AS total FROM orders GROUP BY customer")
  .map(row -> new CustomerStats(row.get("customer", String.class), ...))
  .all();   // Flux<CustomerStats>
```

### Axın və backpressure

```java
db.sql("SELECT ... FROM orders o JOIN product p ... ORDER BY o.id LIMIT :limit")
  .filter(statement -> statement.fetchSize(250))   // PostgreSQL cursor-u ilə 250-lik hissələr
  .map(...).all();                                  // Flux<OrderLine>

@GetMapping(path = "/api/orders/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
Flux<OrderLine> stream(...)
```

- 100 000 sətrin ilk sətri ~100 ms-də gəlir: sorğu hələ bitməyib, nəticə isə yaddaşda heç vaxt tam yığılmır.
- `fetchSize` olmasa, driver bütün nəticəni bir dəfəyə istəyir. Backpressure yalnız bazanın hissə-hissə göndərdiyi hallarda işləyir.
- **Backpressure:** subscriber yalnız 100 sətir istəyirsə, bazadan da təxminən o qədər oxunur (test: 100 000-dən <500). `curl --limit-rate 20k` ilə oxuyan client-də oxuma TCP buferləri dolan kimi (~4 MB, ~41 000 sətir) dayandı və 9.9 MB-ın hamısı oxunmadı.
- **Cancel:** client bağlantını kəsəndə (brauzerdə abort) server bunu `cancel` kimi alır və bazadan oxumağı dayandırır. Demo-da 1000 sətirdən sonra abort edildikdə 100 000-dən ~1000-1040 sətir oxundu.
- Brauzer tələsi: Chrome şəbəkə məlumatını JavaScript-in oxuma sürətindən asılı olmayaraq öz buferinə yığır. Ona görə JS-də "yavaş oxumaq" server-ə backpressure kimi çatmır (yoxladım: server hər şeyi göndərdi). Backpressure-i server-ə TCP səviyyəsində yavaş client (`curl --limit-rate`) və ya abort göstərir.

### Reaktiv tranzaksiya

```java
@Transactional(transactionManager = "connectionFactoryTransactionManager")
public Mono<Order> place(String customer, long productId, int quantity) {
    return db.sql("UPDATE product SET stock = stock - :q WHERE id = :id AND stock >= :q RETURNING price")
             ...one()
             .switchIfEmpty(Mono.error(() -> new OutOfStockException(...)))
             .flatMap(price -> orders.save(new Order(...)));
}
```

- `@Transactional` reaktiv metodlarda da işləyir (`R2dbcTransactionManager`). Tranzaksiya `ThreadLocal`-da yox, **Reactor context**-də saxlanılır, çünki iş thread-dən thread-ə keçə bilər.
- Stok bir atomar `UPDATE ... WHERE stock >= :q` ilə azalır: iki paralel sifariş eyni son malı ala bilmir.
- `Mono.error` tranzaksiyanı rollback edir (testdə: 409-dan sonra stok dəyişmir).
- Transaction manager-in adı yazılıb, çünki müqayisə üçün JDBC pool da var və Boot ikinci (JDBC) transaction manager yaradır. Yalnız R2DBC olan tətbiqdə `@Transactional` kifayətdir.

### Migration niyə JDBC ilədir?

Flyway və Liquibase JDBC ilə işləyir; R2DBC versiyaları yoxdur. Bu, problem deyil: migration start-da bir dəfə işləyir. `spring.flyway.url` Flyway-ə ayrıca JDBC bağlantısı verir.

Boot R2DBC olanda JDBC `DataSource` yaratmır. Müqayisə endpoint-ləri üçün JDBC pool `BlockingJdbcConfig`-də əl ilə qurulub. Real R2DBC tətbiqində bu pool olmur.

## R2DBC, yoxsa JDBC + virtual thread?

| | WebFlux + R2DBC | Spring MVC + virtual thread + JDBC/JPA |
|---|---|---|
| Çoxlu eyni vaxtlı yavaş sorğu | ✅ | ✅ (virtual thread gözləyəndə OS thread-i buraxır) |
| JPA (əlaqələr, lazy loading) | ❌ | ✅ |
| Backpressure ilə axın | ✅ təbii | Mümkündür (`StreamingResponseBody`, `Stream`), amma əl ilə |
| Kod və debug | Çətin (operatorlar, stack trace) | Sadə, adi kod |
| Ekosistem | Daha kiçik | Tam (bütün driver-lər, alətlər) |

**Qayda:** tətbiq artıq WebFlux-dadırsa (gateway, BFF, streaming, çoxlu uzun bağlantı), bazaya R2DBC ilə gedin; JDBC-ni heç vaxt event loop-da çağırmayın. Yeni, adi CRUD servisi üçün isə Java 21+ ilə Spring MVC + virtual thread + JPA çox vaxt daha sadə seçimdir (bax: [webflux-streaming](../webflux-streaming/README.md), [bələdçinin sonsözü](../docs/18-secim.md)).

## Testlər

Testlər Testcontainers ilə real PostgreSQL qaldırır, ona görə **Docker lazımdır**:

```bash
./gradlew :r2dbc:test
```

| Test | Nəyi yoxlayır |
|---|---|
| `repositoryReadsOneRowAndAPage` | `findById`, səhifələmə |
| `databaseClientRunsHandWrittenAggregates` | `DatabaseClient` ilə `GROUP BY` |
| `failedTransactionLeavesNoTrace` | Stok çatmır → 409, stok dəyişmir; uğurlu sifariş stoku azaldır |
| `streamIsNdjsonAndStartsBeforeTheQueryIsFinished` | `application/x-ndjson`, ilk sətirlər |
| `theDatabaseSendsOnlyWhatTheSubscriberAsksFor` | 100 sətir istənir → bazadan <500 sətir oxunur (100 000-dən) |
| `blockingJdbcOnTheEventLoopIsMuchSlowerThanR2dbc` | Event loop-da JDBC ≥2 dəfə yavaş; offloaded JDBC >10 thread, R2DBC ≤4 thread |

## Tələlər

- **Gizli bloklama.** Reaktiv zəncirdə bir JDBC çağırışı, `block()`, `Thread.sleep` və ya bloklayan HTTP client bütün event loop-u dayandırır. Testlərdə [BlockHound](https://github.com/reactor/BlockHound) belə çağırışları tutur.
- **`fetchSize` olmadan "axın".** Driver bütün nəticəni yaddaşa yükləyir; `Flux` görünüşü aldadıcıdır.
- **Pool ölçüsü.** R2DBC thread-ləri qənaət edir, amma bazanın bağlantı limiti qalır. Pool-u bazanın `max_connections` dəyərinə görə seçin.
- **`@Transactional` və `subscribe()`.** Tranzaksiya yalnız qaytarılan `Mono`/`Flux`-a abunə olunanda işləyir. Metodun içində ayrıca `.subscribe()` etsəniz, həmin iş tranzaksiyadan kənarda qalır.
- **Event loop sayı.** Demo üçün 4-ə sabitlənib (`reactor.netty.ioWorkerCount`); real tətbiqdə default (nüvə sayı) saxlanılır.
