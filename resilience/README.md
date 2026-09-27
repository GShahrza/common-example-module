# resilience: Resilience4j ilə dayanıqlı xarici çağırışlar

Mikroservis xarici servisləri (bank, ödəniş, SMS, partnyor API, digər daxili servislər) çağırır və onlar **mütləq** nə vaxtsa ləngiyəcək, səhv verəcək və ya tamamilə düşəcək. Sual onların düşüb-düşməməsi deyil, sizin servisinizin bu vaxt nə edəcəyidir:

- Hər sorğu 30 saniyə asılı qalır, thread-lər bitir və **sizin** servisiniz də düşür (cascading failure).
- Ya da tez imtina edir, köhnə məlumatla işləməyə davam edir və partnyor düzələndə özü bərpa olunur.

Bu modul ikincisini göstərir.

| Pattern | Hansı problemi həll edir | Konfiqurasiya |
|---|---|---|
| **Timeout** | Asılı qalan sorğu thread-i əbədi tutur | `partner.read-timeout: 1s` |
| **Retry** | Müvəqqəti xəta (503, timeout) bir dəfə təkrarla düzəlir | 3 cəhd, 200 → 400 ms (exponential backoff) |
| **Circuit Breaker** | Düşmüş servisi hər sorğuda yenidən yoxlamaq ona da, bizə də zərərdir | son 10 çağırışın ≥50%-i uğursuzdursa: 10 s fail-fast |
| **Rate Limiter** | Partnyor bizə saniyədə N sorğu icazə verir | 5 / s |
| **Bulkhead** | Bir yavaş asılılıq bütün thread-ləri tutmasın | eyni anda maks 5 çağırış |
| **Fallback** | İstifadəçi səhv səhifəsi yox, köhnə, amma işlək məlumat görsün | son uğurlu məzənnə, `stale: true` |

## İşə salma qaydası

Heç bir xarici servis lazım deyil: "partnyor API" (`PartnerSimulator`) eyni tətbiqdədir, amma **HTTP ilə** çağırılır.

```bash
./gradlew :resilience:bootRun
```

və ya Docker ilə:

```bash
docker build -f resilience/Dockerfile -t resilience .
docker run --rm -p 8089:8089 resilience
```

**http://localhost:8089** ünvanında partnyorun vəziyyətini dəyişin və sorğu göndərin. Tövsiyə olunan ssenari:

1. **OK → "1 sorğu"**: canlı cavab, 1 cəhd, circuit `CLOSED`.
2. **FLAKY → "1 sorğu"**: 503, 503, 200; Retry sayəsində istifadəçi xəta görmür (3 cəhd).
3. **ERROR → "10 ardıcıl"**: ilk sorğular 3 cəhddən sonra fallback-ə düşür, 5 çağırışdan sonra circuit `OPEN` olur. Sonrakı sorğular **0 cəhd, 0 ms**: partnyora heç getmirik.
4. **OK**, 10 saniyə gözləyin: circuit `HALF_OPEN` olur, 3 uğurlu sorğudan sonra isə `CLOSED`.
5. **SLOW**: hər cəhd 1 s timeout ilə kəsilir; 3 cəhddən sonra fallback.
6. **BAD_REQUEST**: 400 bizim səhvimizdir, təkrar kömək etməz. Retry olmur, circuit-ə təsir etmir, cavab `502` olur.
7. **OK → "12 paralel"**: 5-i keçir, qalanları rate limiter tərəfindən dərhal rədd edilir (`RequestNotPermitted`).

Terminaldan:

```bash
curl -X PUT localhost:8089/partner/mode -H 'Content-Type: application/json' -d '{"mode":"ERROR"}'
curl localhost:8089/api/rates
curl localhost:8089/actuator/health                 # circuitBreakers: CIRCUIT_OPEN
curl localhost:8089/actuator/circuitbreakerevents   # hər keçid və xəta
curl 'localhost:8089/actuator/metrics/resilience4j.circuitbreaker.state?tag=state:open'
```

---

## Necə işləyir

### Annotasiyalar və sıra

```java
@Retry(name = "partner", fallbackMethod = "fallback")
@CircuitBreaker(name = "partner")
@RateLimiter(name = "partner")
@Bulkhead(name = "partner")
public Rates fetch() { ... http.get().uri("/partner/rates") ... }
```

Resilience4j bunları **sabit sırada** bükür (xaricdən daxilə):

```
Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( metod ) ) ) ) )
```

Nəticələri:

- **Hər retry cəhdi circuit breaker-dən keçir və orada sayılır.** FLAKY rejimində bir istifadəçi sorğusu 3 çağırış edir, ona görə circuit gözləniləndən tez açıla bilər. Bu, düzgün davranışdır: partnyor həqiqətən 3 dəfə uğursuz oldu.
- Circuit açıqdırsa və ya rate limit dolubsa, cəhd şəbəkəyə getmədən rədd edilir (`CallNotPermittedException`, `RequestNotPermitted`). Bu exception-lar `retry-exceptions` siyahısında deyil, ona görə retry edilmir və dərhal fallback-ə keçir.
- `fallbackMethod` ən xaricdəki annotasiyada (`@Retry`) yazılıb. Daxildəki birində (məs. `@CircuitBreaker`) olsaydı, fallback xətanı "udardı" və Retry heç vaxt işə düşməzdi.

### Nəyi retry etməli, nəyi yox

```yaml
retry-exceptions:  [ResourceAccessException, HttpServerErrorException]   # timeout, 5xx
ignore-exceptions: [HttpClientErrorException]                            # 4xx
```

- **Timeout, 503, 502** müvəqqəti ola bilər, retry mənalıdır.
- **400, 404, 422** bizim sorğumuzdadır: 100 dəfə göndərsək də eyni cavabı alacağıq. Circuit breaker-də də `ignore-exceptions` siyahısındadır, çünki bizim bug-ımız partnyorun "sağlamlığını" pisləşdirməməlidir.
- **Idempotent olmayan əməliyyatlar** (ödəniş, sifariş yaratmaq): timeout-dan sonra retry ikiqat ödəniş yarada bilər, çünki sorğu çatmış, cavab isə itmiş ola bilər. Belə hallarda ya partnyora idempotency açarı göndərin, ya da retry etməyin.

**Exponential backoff** (200 ms, 400 ms, ...) düşmüş servisi "boğmamaq" üçündür. Çox instance-lı sistemlərdə **jitter** (təsadüfi gecikmə) də əlavə edin (`randomized-wait-factor`), yoxsa bütün instance-lar eyni anda yenidən vurar.

### Circuit breaker-in vəziyyətləri

```
        ≥50% uğursuz (son 10-dan, min 5)
CLOSED ─────────────────────────────────► OPEN ──(10 s)──► HALF_OPEN
   ▲                                        ▲                  │
   │            3 sınaq çağırışı uğurlu     │   sınaq uğursuz  │
   └────────────────────────────────────────┼──────────────────┘
                                            └──────────────────┘
```

- **CLOSED:** hər şey normaldır, çağırışlar gedir və nəticələri sayılır.
- **OPEN:** çağırış **edilmir**, dərhal `CallNotPermittedException` atılır. Bu, iki tərəfi qoruyur: partnyor bərpa olmaq üçün vaxt qazanır, biz isə thread-lərimizi timeout gözləməyə sərf etmirik.
- **HALF_OPEN:** bir neçə sınaq çağırışı buraxılır. Uğurlu olarsa `CLOSED`, olmasa yenidən `OPEN`.

Yalnız xətalar deyil, **yavaş** çağırışlar da sayılır (`slow-call-duration-threshold`). Hər dəfə 900 ms-də cavab verən servis texniki olaraq "işləyir", amma sizin servisinizi yavaşladır.

### Timeout: hər şeyin əsası

Timeout olmadan retry və circuit breaker işləmir: asılı qalan çağırış heç vaxt "uğursuz" olmur, sadəcə gözləyir. `RestClient`-ə read timeout verilib:

```java
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(Duration.ofMillis(500));
factory.setReadTimeout(readTimeout);   // 1 s
```

Ümumi gözləmə müddəti = cəhd sayı × timeout + backoff-lar. Burada 3 × 1 s + 0.6 s ≈ 3.6 s-dir. Bu dəyər sizi çağıranın (frontend, gateway) öz timeout-undan az olmalıdır.

### Bulkhead və rate limiter

- **Bulkhead** (gəmi bölmələri kimi): partnyor yavaşladıqda sorğuların hamısı onu gözləyə bilər və servisin digər hissələri üçün heç nə qalmaz. Bulkhead eyni anda ən çox 5 çağırışa icazə verir; qalanları dərhal rədd edir (`BulkheadFullException` → fallback). Virtual thread-lərlə thread-lər ucuzdur, amma partnyorun bağlantı limiti, yaddaş və DB bağlantıları ucuz deyil.
- **Rate limiter** partnyorun bizə verdiyi kvotanı (məs. SMS provayderi: 5 / s) qoruyur. Onu aşsaq, partnyor 429 qaytarar və ya bizi bloklayar.

### Fallback: graceful degradation

```java
Rates fallback(Throwable error) {
    if (error instanceof HttpClientErrorException e) throw e;   // bizim bug: gizlətmə
    return lastGood with stale = true, reason = error;
}
```

Fallback biznes qərarıdır, texniki deyil:

| Məlumat | Yaxşı fallback |
|---|---|
| Valyuta məzənnəsi, hava, tövsiyələr | Son uğurlu dəyər (+ "yenilənmə vaxtı" göstərmək) |
| Məhsul tövsiyələri | Populyar məhsulların statik siyahısı |
| Ödəniş | **Fallback yoxdur:** aydın xəta mesajı, ya da sifarişi "gözləmədə" saxlayıb sonra yenidən cəhd (outbox, bax [kafka-events](../kafka-events)) |

### Monitorinq

Circuit-in açılması **alert** olmalıdır: bu, asılılığınızın düşdüyü deməkdir. Aşağıdakılar avtomatik gəlir:

- `/actuator/health` → `circuitBreakers` (`register-health-indicator: true`);
- metrikalar: `resilience4j.circuitbreaker.state`, `.calls`, `resilience4j.retry.calls`, `resilience4j.ratelimiter.available.permissions`...;
- `/actuator/circuitbreakerevents`: hər keçid və xəta.

[observability](../observability) modulu ilə birlikdə bu metrikalar Grafana-ya düşür.

Circuit breaker-in health-i readiness probe-a qoşulmamalıdır: partnyor düşəndə Kubernetes **sizin** pod-larınızı trafikdən çıxarmamalıdır.

## Spring Framework 7-nin öz retry-ı

Spring Framework 7 sadə hallar üçün Resilience4j-siz də iş görür (`@EnableResilientMethods` ilə):

```java
@Retryable(includes = ResourceAccessException.class, maxRetries = 2, delay = 200, multiplier = 2)
@ConcurrencyLimit(5)   // bulkhead-in sadə variantı
public Rates fetch() { ... }
```

Circuit breaker, rate limiter, time limiter və zəngin metrikalar isə yalnız Resilience4j-dədir. Qısa qayda: yalnız retry lazımdırsa, Spring-in öz annotasiyası kifayətdir; xarici asılılığı tam qorumaq lazımdırsa, Resilience4j istifadə edin.

## Konfiqurasiya

Hamısı `application.yml`-dədir və koda toxunmadan dəyişdirilə bilər. Hər parametrin yanında izahı var.

## Testlər

```bash
./gradlew :resilience:test
```

| Test | Nəyi yoxlayır |
|---|---|
| `healthyPartnerIsCalledOnce` | Normal halda 1 HTTP çağırış |
| `transientErrorsAreRetried` | 503, 503, 200 → uğurlu cavab, 3 cəhd |
| `clientErrorIsNeitherRetriedNorCountedAgainstThePartner` | 400: 1 cəhd, circuit-də uğursuzluq sayılmır, 502 qaytarılır |
| `timeoutIsRetriedThenTheLastGoodValueIsServed` | Timeout → 3 cəhd → köhnə dəyər (`stale`) |
| `failuresOpenTheCircuitWhichThenFailsFastWithoutCallingThePartner` | Circuit `OPEN` → 0 cəhd, <100 ms, partnyora heç bir çağırış getmir |
| `circuitClosesAgainWhenThePartnerRecovers` | `OPEN` → `HALF_OPEN` → 3 uğurlu çağırış → `CLOSED` |
| `burstAboveTheLimitIsRejectedWithoutCallingThePartner` | 12 paralel sorğudan limit qədəri keçir, qalanları `RequestNotPermitted` alır |

## Tələlər

- **Self-invocation.** Annotasiyalar proxy ilə işləyir; eyni sinifdən `this.fetch()` çağırışı onları keçir.
- **Timeout-suz retry.** Asılı qalan çağırış heç vaxt uğursuz olmur, ona görə retry və circuit breaker də işə düşmür.
- **Retry fırtınası.** 3 servis zəncirində hər biri 3 dəfə retry etsə, sonuncu servis 27 sorğu alır. Retry-ı yalnız bir qatda (adətən kənara ən yaxın olanda) edin.
- **Circuit breaker-i hər yerdə eyni ad ilə paylaşmaq.** Hər asılılığın öz instance-ı olmalıdır (`partner`, `sms`, `payment`), yoxsa bir servisin düşməsi digərlərinin də circuit-ini açar.
- **Fallback-də xəta.** Fallback-in özü də xəta atsa, istifadəçi daha anlaşılmaz xəta görür. Fallback sadə və etibarlı olmalıdır.
