# 22. Resilience4j: xarici servis "xəstələnəndə"

[← 21. Redis](21-redis.md) · [Mündəricat](README.md) · Növbəti: [23. Spring Security və JWT →](23-security-jwt.md)

**Hissə IV: Əlavə mövzular** · **Kod:** [`RatesClient`](../resilience/src/main/java/io/github/gshahrza/resilience/RatesClient.java), [`PartnerSimulator`](../resilience/src/main/java/io/github/gshahrza/resilience/PartnerSimulator.java), [`application.yml`](../resilience/src/main/resources/application.yml) · **Demo:** http://localhost:8089

```bash
./gradlew :resilience:bootRun      # xarici servis lazım deyil: "partnyor" simulyatordur
```

---

## Həyatdan analogiya

Evin elektrik şitində **avtomatlar** var. Kəsilmiş kabeldə qısa qapanma olanda avtomat **düşür** və həmin xətti kəsir. Nəticədə kabel yanmır, ev yanmır, digər otaqlarda isə işıq yanmağa davam edir.

- Avtomat düşəndən sonra onu **dərhal** qaldırmırsınız: əvvəl gözləyirsiniz, sonra **ehtiyatla bir dəfə** yandırıb baxırsınız.
- Hər otağın **öz** avtomatı var: mətbəxdəki problem yataq otağını qaranlıqda qoymur.
- Lampa bir dəfə yanıb-sönürsə, açarı **bir-iki dəfə** yenidən basırsınız. Yüz dəfə basmırsınız.
- İşıq tamam gedibsə, **şam** yandırırsınız: tam işıq deyil, amma qaranlıqdan yaxşıdır.

Resilience4j bu şitdir: avtomat **circuit breaker**, "gözlə, sonra ehtiyatla yoxla" **half-open**, otaq-otaq avtomatlar **bulkhead**, açarı bir-iki dəfə basmaq **retry**, şam isə **fallback**-dir.

## Problem: asılılıq düşəndə

Servisiniz bankın valyuta API-sini çağırır. Bir gün API ləngiməyə başlayır: cavab 30 saniyə çəkir.

1. Hər gələn sorğu bir thread tutub 30 saniyə gözləyir.
2. Thread pool dolur və sizin **bütün** endpoint-ləriniz cavab verməyi dayandırır, hətta valyuta ilə əlaqəsi olmayanlar da.
3. Sizi çağıran servislər də gözləyir və onların da thread-ləri dolur.
4. Bir asılılığın problemi bütün sistemi yıxır: bu, **zəncirvari çökmədir** (cascading failure).

Asılılığın düşüb-düşməyəcəyi sual deyil: **mütləq** düşəcək. Sual bu vaxt sizin servisinizin nə edəcəyidir:

- asılı qalıb özü də düşür,
- yoxsa tez imtina edir, köhnə məlumatla işləməyə davam edir və asılılıq düzələndə özü bərpa olunur.

## Pattern-lər bir baxışda

| Pattern | Sual | Bu modulda |
|---|---|---|
| **Timeout** | Nə qədər gözləyək? | `partner.read-timeout: 1s` |
| **Retry** | Müvəqqəti xəta idisə, yenidən cəhd edək? | 3 cəhd, 200 → 400 ms |
| **Circuit breaker** | Asılılıq düşübsə, onu yormağı dayandıraq? | ≥50% uğursuz → 10 s fail-fast |
| **Rate limiter** | Asılılığa saniyədə nə qədər sorğu göndərə bilərik? | 5 / s |
| **Bulkhead** | Bir asılılıq bütün resurslarımızı tuta bilərmi? | eyni anda maks 5 çağırış |
| **Fallback** | Cavab ala bilmədikdə istifadəçiyə nə göstərək? | son uğurlu məzənnə, `stale: true` |

## Həll, addım-addım

`resilience` modulunda "partnyor API" (`PartnerSimulator`) eyni tətbiqdədir, amma **HTTP ilə** çağırılır. Onun vəziyyəti demo səhifədən dəyişdirilir:

| Rejim | Partnyor nə edir |
|---|---|
| `OK` | 50 ms-də cavab verir |
| `SLOW` | 3 saniyə gözləyir (bizim timeout 1 s-dir) |
| `ERROR` | Həmişə 500 qaytarır |
| `FLAKY` | 503, 503, 200, 503, 503, 200... |
| `BAD_REQUEST` | 400: sorğunu biz səhv göndərmişik |

Qorunan çağırış belə görünür:

```java
@Retry(name = "partner", fallbackMethod = "fallback")
@CircuitBreaker(name = "partner")
@RateLimiter(name = "partner")
@Bulkhead(name = "partner")
public Rates fetch() {
    Map<String, Object> body = http.get().uri("/partner/rates").retrieve().body(...);
    ...
}
```

### 1. Timeout: hər şeyin təməli

```java
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(Duration.ofMillis(500));
factory.setReadTimeout(Duration.ofSeconds(1));
```

Timeout olmadan qalan pattern-lər işləmir: asılı qalan çağırış heç vaxt "uğursuz" olmur, sadəcə gözləyir. Retry onu təkrarlaya bilməz, circuit breaker isə saya bilməz.

Timeout-u necə seçmək:

- Asılılığın **normal** p99 latency-sinə baxın (məs. 300 ms) və bir az ehtiyat qoyun.
- **Ümumi büdcəni** hesablayın: `cəhd sayı × timeout + backoff-lar` = 3 × 1 s + 0.6 s ≈ 3.6 s. Bu, sizi çağıranın (frontend, gateway) öz timeout-undan **az** olmalıdır. Yoxsa client artıq imtina edib, siz isə hələ də cəhd edirsiniz.

Demo-da `SLOW` rejimi: hər cəhd 1 saniyədə kəsildi, 3 cəhddən sonra fallback işlədi, cəmi **3620 ms**.

### 2. Retry: yalnız mənası olanda

```yaml
resilience4j.retry.instances.partner:
  max-attempts: 3                    # 1 çağırış + 2 təkrar
  wait-duration: 200ms
  enable-exponential-backoff: true   # 200 ms, 400 ms
  exponential-backoff-multiplier: 2
  retry-exceptions: [ResourceAccessException, HttpServerErrorException]   # timeout, 5xx
  ignore-exceptions: [HttpClientErrorException]                          # 4xx
```

- **Retry et:** müvəqqəti xətalar (timeout, 502, 503, bağlantı kəsilməsi).
- **Retry etmə:** 400, 404, 422. Bunlar bizim sorğumuzdadır, 100 dəfə göndərsək də eyni cavab gələcək. Demo-da `BAD_REQUEST`: 1 cəhd, dərhal `502`.
- **Idempotent olmayan əməliyyatlar** (ödəniş, sifariş yaratmaq): timeout-dan sonra retry ikiqat ödəniş yarada bilər, çünki sorğu çatmış, cavab isə itmiş ola bilər. Belə hallarda ya partnyora idempotency açarı göndərin, ya da retry etməyin.
- **Exponential backoff** düşmüş servisi "boğmamaq" üçündür. Bir neçə instance varsa, **jitter** (təsadüfi gecikmə) də əlavə edin, yoxsa hamısı eyni millisaniyədə yenidən vurar.

Demo-da `FLAKY`: 503, 503, 200. İstifadəçi xəta görmür, cavab 3-cü cəhddə gəlir.

### 3. Circuit breaker: düşmüş servisi rahat buraxmaq

```yaml
resilience4j.circuitbreaker.instances.partner:
  sliding-window-type: COUNT_BASED
  sliding-window-size: 10              # son 10 çağırışa bax
  minimum-number-of-calls: 5           # ... amma ən az 5 çağırışdan sonra qərar ver
  failure-rate-threshold: 50           # ≥50% uğursuz → OPEN
  slow-call-duration-threshold: 800ms  # bundan yavaş çağırış "yavaş" sayılır
  slow-call-rate-threshold: 80         # ≥80% yavaş → OPEN
  wait-duration-in-open-state: 10s
  permitted-number-of-calls-in-half-open-state: 3
  automatic-transition-from-open-to-half-open-enabled: true
  ignore-exceptions: [HttpClientErrorException]   # bizim bug-ımız partnyorun "sağlamlığını" pisləşdirməsin
```

```
        ≥50% uğursuz (son 10-dan, min 5)
CLOSED ─────────────────────────────────► OPEN ──(10 s)──► HALF_OPEN
   ▲                                        ▲                  │
   │         3 sınaq çağırışı uğurlu        │  sınaq uğursuz   │
   └────────────────────────────────────────┼──────────────────┘
                                            └──────────────────┘
```

- **CLOSED:** hər şey normaldır; çağırışlar gedir və nəticələri sayılır.
- **OPEN:** çağırış **edilmir**, dərhal `CallNotPermittedException` atılır. Bu, iki tərəfi qoruyur: partnyor bərpa olmaq üçün vaxt qazanır, biz isə thread-lərimizi timeout gözləməyə sərf etmirik.
- **HALF_OPEN:** bir neçə sınaq çağırışı buraxılır. Uğurlu olsalar `CLOSED`, olmasalar yenidən `OPEN`.

Demo-da ölçülən: `ERROR` rejimində ilk sorğular 3 cəhddən sonra fallback-ə düşdü. 5 çağırışdan sonra circuit `OPEN` oldu (80% uğursuz), növbəti 9 sorğu isə **0 cəhd, 0 ms** ilə cavab aldı: partnyora heç getmədik. `OK` rejiminə keçib 10 saniyə gözlədikdən sonra `HALF_OPEN`, 3 uğurlu çağırışdan sonra isə `CLOSED`.

**Yavaş çağırışlar** da sayılır. Hər dəfə 900 ms-də cavab verən servis texniki olaraq "işləyir", amma sizin servisinizi yavaşladır. `slow-call-*` parametrləri bunu da "xəstəlik" kimi görür.

### 4. Annotasiyaların sırası

Resilience4j annotasiyaları **sabit sırada** bükür, xaricdən daxilə:

```
Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( metod ) ) ) ) )
```

Bunun üç nəticəsi var:

1. **Hər retry cəhdi circuit breaker-dən keçir və orada sayılır.** Bu modulda da müşahidə olundu: `FLAKY` rejimində bir istifadəçi sorğusu 3 çağırış etdi və circuit gözləniləndən tez açıldı. Bu, düzgündür: partnyor həqiqətən 3 dəfə uğursuz oldu.
2. **Circuit açıqdırsa və ya rate limit dolubsa**, cəhd şəbəkəyə getmədən rədd edilir. Bu exception-lar (`CallNotPermittedException`, `RequestNotPermitted`) `retry-exceptions` siyahısında deyil, ona görə retry edilmir və dərhal fallback-ə keçir.
3. **`fallbackMethod` ən xaricdəki annotasiyada** (`@Retry`) yazılıb. Daxildəki birində (məs. `@CircuitBreaker`) olsaydı, fallback xətanı "udardı" və retry heç vaxt işə düşməzdi.

Sıra `resilience4j.*.<x>-aspect-order` parametrləri ilə dəyişdirilə bilər, amma default sıra çox vaxt düzgündür.

### 5. Rate limiter və bulkhead

```yaml
resilience4j.ratelimiter.instances.partner:
  limit-for-period: 5          # partnyor bizə saniyədə 5 sorğu icazə verir
  limit-refresh-period: 1s
  timeout-duration: 0          # limit dolubsa gözləmə, dərhal rədd et
resilience4j.bulkhead.instances.partner:
  max-concurrent-calls: 5      # eyni anda maks 5 thread partnyoru gözləsin
  max-wait-duration: 0
```

- **Rate limiter** partnyorun kvotasını (SMS provayderi: 5 / s) qoruyur. Onu aşsaq, partnyor 429 qaytarar və ya bizi bloklayar. Demo-da 12 paralel sorğudan **5-i** keçdi, 7-si dərhal `RequestNotPermitted` aldı.
- **Bulkhead** gəmi bölmələri kimi işləyir: bir bölmə su ilə dolsa, gəmi batmır. Partnyor yavaşlasa, sorğuların hamısı onu gözləyə bilər və servisin digər hissələri üçün heç nə qalmaz. Bulkhead eyni anda ən çox 5 çağırışa icazə verir, qalanlarını dərhal rədd edir. Virtual thread-lərlə thread-lər ucuzdur, amma partnyorun bağlantı limiti, yaddaş və DB bağlantıları ucuz deyil.

Fərq: rate limiter **zaman vahidində** neçə sorğu olduğunu, bulkhead isə **eyni anda** neçə sorğu olduğunu məhdudlaşdırır.

### 6. Fallback: graceful degradation

```java
Rates fallback(Throwable error) {
    if (error instanceof HttpClientErrorException clientError) {
        throw clientError;              // 4xx bizim bug-dır: köhnə məlumatla gizlətmə
    }
    Rates last = lastGood.get();
    if (last == null) {
        throw new PartnerUnavailableException(...);   // göstərəcək heç nə yoxdur: 503
    }
    return new Rates(last.rates(), true /* stale */, ..., reason, last.fetchedAt());
}
```

Fallback texniki yox, **biznes** qərarıdır:

| Məlumat | Yaxşı fallback |
|---|---|
| Valyuta məzənnəsi, hava | Son uğurlu dəyər və onun yenilənmə vaxtı |
| Məhsul tövsiyələri | Populyar məhsulların statik siyahısı |
| Profil şəkli | Default avatar |
| Ödəniş | **Fallback yoxdur:** aydın xəta, ya da "gözləmədə" saxlayıb sonra yenidən cəhd (outbox, 20-ci fəsil) |

Fallback-in cavabında məlumatın köhnə olduğu (`stale: true`, `fetchedAt`) görünməlidir. Köhnə məzənnəni təzə kimi göstərmək xətadan da pisdir.

## Monitorinq

Circuit-in açılması **alert** olmalıdır: bu, asılılığınızın düşdüyü deməkdir. Aşağıdakılar avtomatik gəlir:

- `/actuator/health` → `circuitBreakers` (`register-health-indicator: true`);
- metrikalar: `resilience4j.circuitbreaker.state`, `resilience4j.circuitbreaker.calls{kind=failed|not_permitted|...}`, `resilience4j.retry.calls`, `resilience4j.ratelimiter.available.permissions`, `resilience4j.bulkhead.available.concurrent.calls`;
- `/actuator/circuitbreakerevents`: hər vəziyyət keçidi və xəta.

Circuit breaker-in health-ini Kubernetes **readiness** probe-una qoşmayın: partnyor düşəndə Kubernetes **sizin** pod-larınızı trafikdən çıxarmamalıdır.

## Spring Framework 7-nin öz resilience annotasiyaları

Sadə hallar üçün Resilience4j-siz də iş görmək olur (`@EnableResilientMethods`):

```java
@Retryable(includes = ResourceAccessException.class, maxRetries = 2, delay = 200, multiplier = 2)
@ConcurrencyLimit(5)            // bulkhead-in sadə variantı
public Rates fetch() { ... }
```

Circuit breaker, rate limiter və zəngin metrikalar isə yalnız Resilience4j-dədir. Qısa qayda: yalnız retry lazımdırsa, Spring-in öz annotasiyası kifayətdir; xarici asılılığı tam qorumaq lazımdırsa, Resilience4j istifadə edin.

## Tələlər

- **Timeout-suz retry.** Asılı qalan çağırış heç vaxt uğursuz olmur, ona görə retry və circuit breaker işə düşmür.
- **Retry fırtınası.** A → B → C zəncirində hər qat 3 dəfə retry etsə, C 27 sorğu alır. Retry-ı yalnız bir qatda edin, adətən kənara ən yaxın olanda.
- **Hər şeyi retry etmək.** 4xx, validasiya və biznes xətaları retry ilə düzəlmir, yalnız yükü artırır.
- **Idempotent olmayan əməliyyatı retry etmək.** Nəticə ikiqat ödəniş və ya ikiqat sifarişdir.
- **Bütün asılılıqlar üçün bir circuit breaker.** SMS servisinin düşməsi ödəniş circuit-ini də açar. Hər asılılığın öz instance-ı olmalıdır.
- **Default dəyərlər.** Circuit breaker-in default-ları çox "tənbəldir": `sliding-window-size` 100, `minimum-number-of-calls` 100, `wait-duration-in-open-state` 60 s. Kiçik trafikdə circuit heç vaxt açılmaya bilər. Rəqəmləri trafiki nəzərə alaraq seçin.
- **Self-invocation.** Annotasiyalar proxy ilə işləyir; eyni sinfin içindən `this.fetch()` çağırışı onların hamısını keçir.
- **Fallback-in içində xəta.** Fallback sadə və etibarlı olmalıdır; onun da xəta verməsi istifadəçiyə daha anlaşılmaz xəta göstərir.
- **Test olunmayan resilience.** Circuit-in açıldığını heç kim görməyibsə, konfiqurasiya çox güman ki işləmir. Bu modulun testləri hər vəziyyəti yoxlayır.

## Yadda saxla

- Asılılıq **mütləq** düşəcək: məqsəd sizin servisin onunla birlikdə düşməməsidir.
- **Timeout** birinci gəlir; o olmadan qalan pattern-lər işləmir.
- **Retry** yalnız müvəqqəti xətalar və idempotent əməliyyatlar üçündür, backoff və jitter ilə, yalnız bir qatda.
- **Circuit breaker** düşmüş asılılığı rahat buraxır və fail-fast edir: CLOSED → OPEN → HALF_OPEN.
- **Rate limiter** "saniyədə neçə", **bulkhead** isə "eyni anda neçə" sualına cavab verir.
- **Fallback** biznes qərarıdır və köhnə məlumat köhnə kimi işarələnməlidir.
- Annotasiyaların sırası: `Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead`; fallback ən xaricdə olur.

## Tapşırıqlar

1. Demo-da `FLAKY` rejimini seçin və "1 sorğu" basın, sonra bir də. Circuit breaker-in "son N çağırış" statistikasına baxın. Niyə iki istifadəçi sorğusundan sonra circuit açıla bilir?
2. `ERROR` rejimində "10 ardıcıl" basın. Neçənci sorğudan sonra cəhd sayı 0 oldu? `minimum-number-of-calls`-u 10 edin və təkrarlayın.
3. `@Retry`-dakı `fallbackMethod`-u `@CircuitBreaker`-ə köçürün və `FLAKY` rejimini sınayın. Retry niyə işləmədi?
4. `max-attempts`-i 5, `read-timeout`-u 2 s edin və `SLOW` rejimində ümumi müddəti ölçün. Bu, frontend-in 5 saniyəlik timeout-u üçün qəbuledilərdirmi?
5. (Çətin) Retry-a jitter əlavə edin (`enable-randomized-wait: true`, `randomized-wait-factor: 0.5`) və 20 paralel sorğu ilə partnyora çatan cəhdlərin vaxtlarını loglayın. Jitter-siz variantla müqayisə edin.

---

## Müsahibə sualları

Ən çox verilən resilience sualları. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın.

### Əsaslar

<details>
<summary><b>1. Resilience (dayanıqlılıq) nədir və mikroservislərdə niyə vacibdir?</b></summary>

Sistemin asılılıqları düşəndə və ya yavaşlayanda işləməyə davam edə bilməsi, qismən də olsa, və asılılıq düzələndə özünün bərpa olunmasıdır. Mikroservislərdə hər sorğu şəbəkə üzərindən bir neçə servisə gedir; şəbəkə və servislər mütləq nə vaxtsa xəta verəcək. Qorunma olmadan bir servisin problemi thread pool-ları doldurur və zəncirvari çökməyə (cascading failure) çevrilir.
</details>

<details>
<summary><b>2. Resilience4j nədir? Hystrix ilə fərqi nədir?</b></summary>

Java üçün yüngül, modul-modul (circuit breaker, retry, rate limiter, bulkhead, time limiter, cache) resilience kitabxanasıdır. Funksional dekoratorlar və Spring Boot annotasiyaları ilə işləyir, Micrometer metrikaları verir. **Netflix Hystrix** 2018-dən maintenance rejimindədir; o, ağır idi, hər çağırışı ayrıca thread pool-da icra edirdi. Resilience4j isə default olaraq çağıranın thread-ində işləyir və yalnız lazım olan modulları əlavə etməyə imkan verir.
</details>

<details>
<summary><b>3. Cascading failure nədir və necə qarşısı alınır?</b></summary>

Bir servisin problemi onu çağıranları da sıradan çıxarır: gözləyən çağırışlar thread-ləri, bağlantıları və yaddaşı tutur, sonra onları çağıranlar da eyni vəziyyətə düşür. Qarşısını almaq üçün:

- **timeout** (sonsuz gözləmə yoxdur);
- **circuit breaker** (düşmüş servisə çağırış getmir);
- **bulkhead** (bir asılılıq bütün resursları tuta bilmir);
- **fallback** (qismən cavab).
</details>

<details>
<summary><b>4. Timeout niyə ən vacib pattern hesab olunur?</b></summary>

Timeout olmadan asılı qalan çağırış heç vaxt bitmir: thread əbədi tutulur, retry onu təkrarlaya bilmir, circuit breaker onu "uğursuz" kimi saya bilmir. Hər şəbəkə çağırışının həm connect, həm də read timeout-u olmalıdır. Timeout asılılığın normal p99 latency-sinə görə seçilir, zəncirin ümumi büdcəsi isə (retry-lar daxil) çağıranın timeout-undan az olmalıdır.
</details>

### Circuit breaker

<details>
<summary><b>5. Circuit breaker-in vəziyyətləri hansılardır?</b></summary>

- **CLOSED:** çağırışlar gedir, nəticələr sayılır.
- **OPEN:** uğursuzluq həddi aşılıb; çağırışlar edilmir, dərhal `CallNotPermittedException` atılır.
- **HALF_OPEN:** gözləmə müddəti bitəndən sonra bir neçə sınaq çağırışı buraxılır; nəticəyə görə `CLOSED` və ya yenidən `OPEN` olur.

Resilience4j-də əlavə vəziyyətlər də var:

- `DISABLED` (həmişə icazə verir);
- `FORCED_OPEN` (həmişə rədd edir);
- `METRICS_ONLY` (yalnız sayır).
</details>

<details>
<summary><b>6. Circuit breaker hansı parametrlərlə konfiqurasiya olunur?</b></summary>

- **Sliding window** (`COUNT_BASED` son N çağırış, və ya `TIME_BASED` son N saniyə) və onun ölçüsü;
- `minimum-number-of-calls`: qərar üçün minimum statistika;
- `failure-rate-threshold` və `slow-call-rate-threshold` / `slow-call-duration-threshold`;
- `wait-duration-in-open-state`;
- `permitted-number-of-calls-in-half-open-state`;
- `record-exceptions` / `ignore-exceptions`: hansı xətalar uğursuzluq sayılır.
</details>

<details>
<summary><b>7. Count-based və time-based sliding window arasında fərq nədir?</b></summary>

**Count-based** son N çağırışın nəticəsinə baxır: trafik azdırsa, köhnə çağırışlar da nəzərə alınır. **Time-based** son N saniyədəki çağırışlara baxır: yüksək trafikdə daha dəqiq və "təzə" mənzərə verir, amma az trafikdə pəncərədə kifayət qədər çağırış olmaya bilər (`minimum-number-of-calls` bunu idarə edir).
</details>

<details>
<summary><b>8. Hansı xətalar circuit breaker-də uğursuzluq sayılmalıdır?</b></summary>

Asılılığın **sağlamlığını** göstərənlər: timeout, bağlantı xətası, 5xx. Bizim səhvimizi göstərənlər isə sayılmamalıdır: 4xx, validasiya, biznes "yox" cavabı. Yoxsa bizim bug-ımız (məs. səhv parametr) sağlam partnyorun circuit-ini açar. Resilience4j-də bunu `record-exceptions`, `ignore-exceptions` və ya `record-failure-predicate` idarə edir.
</details>

<details>
<summary><b>9. Circuit breaker ilə health check arasında fərq nədir?</b></summary>

Health check asılılığı **periodik, ayrıca** sorğu ilə yoxlayır. Circuit breaker isə **real** çağırışların nəticələrinə əsasən, dərhal qərar verir və çağırışları özü bloklayır. Circuit-in vəziyyəti health endpoint-də göstərilə bilər, amma onu readiness probe-a qoşmaq olmaz: asılılıq düşəndə öz pod-larınız trafikdən çıxmamalıdır.
</details>

### Retry

<details>
<summary><b>10. Hansı xətaları retry etmək lazımdır, hansıları yox?</b></summary>

- **Retry et:** müvəqqəti xətalar: timeout, bağlantı kəsilməsi, 502, 503, 504, 429 (`Retry-After` nəzərə alınaraq), deadlock və lock timeout.
- **Retry etmə:** 400, 401, 403, 404, 409, 422, validasiya və biznes xətaları. Onlar təkrarla düzəlmir.

Qayda: xətanın səbəbi **vaxtla keçə bilərsə**, retry mənalıdır.
</details>

<details>
<summary><b>11. Exponential backoff və jitter nədir?</b></summary>

- **Exponential backoff:** hər növbəti cəhddən əvvəl gözləmə artır (200 ms, 400 ms, 800 ms...), ki düşmüş servisə bərpa olmaq üçün vaxt qalsın.
- **Jitter:** gözləməyə təsadüfi hissə əlavə olunur. Çoxlu client eyni anda uğursuz olubsa, jitter olmadan hamısı eyni millisaniyədə yenidən vurur (thundering herd). Jitter bu dalğanı zamanda yayır.
</details>

<details>
<summary><b>12. Retry storm (retry amplification) nədir?</b></summary>

Çağırış zəncirində (A → B → C) hər qat öz retry-ını edir. C düşəndə B hər sorğunu 3 dəfə, A isə B-yə hər sorğunu 3 dəfə göndərir: C 9 sorğu alır, dörd qatda isə 81. Retry düşmüş servisi daha da basır. Həlli:

- retry-ı yalnız bir qatda (adətən kənara ən yaxın olanda) etmək;
- retry büdcəsi (məs. sorğuların ən çox 10%-i retry ola bilər);
- circuit breaker.
</details>

<details>
<summary><b>13. Retry ilə idempotency arasında əlaqə nədir?</b></summary>

Timeout-dan sonra sorğunun server-ə çatıb-çatmadığını bilmirik: cavab itmiş ola bilər. Retry idempotent olmayan əməliyyatı (ödəniş, sifariş yaratmaq) iki dəfə icra edə bilər. Ona görə:

- `GET`, `PUT`, `DELETE` kimi idempotent əməliyyatlar retry edilə bilər;
- `POST` yalnız **idempotency açarı** ilə retry edilir (server eyni açarı ikinci dəfə emal etmir);
- və ya ümumiyyətlə retry edilmir.
</details>

### Rate limiter, bulkhead, time limiter

<details>
<summary><b>14. Rate limiter ilə bulkhead arasında fərq nədir?</b></summary>

- **Rate limiter:** zaman vahidində neçə çağırış olduğunu məhdudlaşdırır (saniyədə 5). Partnyorun kvotasını və ya öz API-nizi qoruyur.
- **Bulkhead:** **eyni anda** neçə çağırışın icra olunduğunu məhdudlaşdırır (maks 5 paralel). Yavaş asılılığın bütün resursları tutmasının qarşısını alır.

Nümunə: saniyədə 5 sorğu, amma hər biri 10 saniyə çəkirsə, rate limiter keçir, eyni anda isə 50 çağırış olur. Bunu yalnız bulkhead dayandırar.
</details>

<details>
<summary><b>15. Semaphore bulkhead ilə thread pool bulkhead arasında fərq nədir?</b></summary>

- **Semaphore bulkhead:** çağıranın thread-ində işləyir, eyni vaxtda icazələrin sayını məhdudlaşdırır. Yüngüldür, sinxron kod üçündür.
- **Thread pool bulkhead:** çağırışı ayrıca, məhdud thread pool-da və növbədə icra edir (`CompletableFuture` qaytarır). Asılılığı çağırandan tam təcrid edir, timeout ilə birləşdirmək asandır, amma thread və context keçidi xərci var.
</details>

<details>
<summary><b>16. Time limiter nədir və HTTP client timeout-undan nə ilə fərqlənir?</b></summary>

`TimeLimiter` asinxron çağırışa (`CompletableFuture`, reaktiv tiplər) vaxt limiti qoyur və vaxt bitəndə gələcəyi ləğv edə bilir. HTTP client-in öz timeout-u isə şəbəkə səviyyəsində işləyir və adətən daha etibarlıdır, çünki bağlantını həqiqətən bağlayır. Sinxron HTTP çağırışlarında client timeout-u əsas qorunmadır. TimeLimiter isə bir neçə addımdan ibarət asinxron əməliyyatın ümumi vaxtını məhdudlaşdırmaq üçündür.
</details>

<details>
<summary><b>17. Rate limiting alqoritmləri hansılardır?</b></summary>

- **Fixed window:** pəncərədə sayğac. Sadədir, amma sərhəddə 2× partlayışa icazə verir.
- **Sliding window log / counter:** daha hamar.
- **Token bucket:** vedrə sabit sürətlə dolur, sorğu bir token götürür; partlayışlara vedrənin ölçüsü qədər icazə verir.
- **Leaky bucket:** sorğular sabit sürətlə emal olunur.

Resilience4j-nin `RateLimiter`-i "N icazə hər dövr" modelidir. Paylanmış (bütün instance-lar üçün ümumi) limit üçün Redis və ya API gateway lazımdır.
</details>

### Fallback və dizayn

<details>
<summary><b>18. Yaxşı fallback necə olmalıdır?</b></summary>

- Sadə və etibarlı: başqa uzaq çağırış etməməli, xəta verməməlidir.
- Biznes baxımından məqbul: keşdəki son dəyər, default dəyər, boş siyahı, "funksiya müvəqqəti əlçatmazdır" mesajı.
- Şəffaf: cavabda məlumatın köhnə və ya natamam olduğu görünməlidir.
- Bəzən ən düzgün fallback aydın xətadır: ödənişi "uğurlu" kimi göstərmək olmaz.
</details>

<details>
<summary><b>19. Fallback hansı xətaları tutmamalıdır?</b></summary>

Bizim bug-larımızı göstərənləri: 4xx (səhv sorğu), validasiya, konfiqurasiya xətaları, `NullPointerException`. Onları köhnə məlumatla gizlətmək problemi aşkar olunmayan edir. Bu modulda fallback `HttpClientErrorException`-u yenidən atır, qalan xətalar (timeout, 5xx, açıq circuit, rate limit) isə köhnə məzənnə ilə qarşılanır.
</details>

<details>
<summary><b>20. Graceful degradation nədir?</b></summary>

Asılılıq düşəndə sistemin tam sıradan çıxması əvəzinə funksionallığın bir hissəsinin azaldılmasıdır. Məsələn, tövsiyələr servisi düşəndə məhsul səhifəsi tövsiyəsiz açılır; axtarış düşəndə kateqoriya naviqasiyası işləyir; məzənnə API-si düşəndə son məlum məzənnə "yenilənmə vaxtı" ilə göstərilir. Kritik yol (sifariş, ödəniş) qorunur, ikinci dərəcəli funksiyalar isə qurban verilir.
</details>

<details>
<summary><b>21. Load shedding nədir?</b></summary>

Servis öz tutumunu aşan yükü **qəbul etmədən** rədd etməsidir (məs. növbə doludursa dərhal 503). Bunun məqsədi hamıya yavaş cavab verməkdənsə, bəzilərinə sürətli "yox" deyib qalanlarına normal xidmət göstərməkdir. Bulkhead, rate limiter və prioritetə görə rədd etmə (kritik olmayan sorğular əvvəl) load shedding-in formalarıdır.
</details>

### Spring və Resilience4j

<details>
<summary><b>22. Resilience4j annotasiyaları hansı sırada tətbiq olunur?</b></summary>

Default olaraq xaricdən daxilə: `Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead → metod`. Nəticələri:

- hər retry cəhdi circuit breaker-də sayılır;
- açıq circuit və ya dolmuş limit cəhdi şəbəkəyə getmədən rədd edir.

Sıra `resilience4j.retry.retry-aspect-order` kimi parametrlərlə dəyişdirilə bilər.
</details>

<details>
<summary><b>23. <code>fallbackMethod</code> hansı annotasiyada olmalıdır və imzası necədir?</b></summary>

Ən xaricdəki annotasiyada (adətən `@Retry`). Daxildəki annotasiyada olsa, xətanı tutur və xarici retry heç vaxt işə düşmür. Fallback metodu eyni sinifdə olur, eyni qaytarma tipinə, eyni parametrlərə və əlavə olaraq sonda bir `Throwable` (və ya konkret exception) parametrinə malik olur. Bir neçə fallback varsa, exception tipinə ən uyğun olanı seçilir.
</details>

<details>
<summary><b>24. Annotasiyalar niyə bəzən işləmir?</b></summary>

- **Self-invocation:** eyni sinifdən çağırış proxy-dən keçmir.
- Metod `private`-dır.
- AOP starter-i yoxdur (Spring Boot 4-də `spring-boot-starter-aspectj`).
- Instance adı konfiqurasiyadakı adla uyğun deyil və default-lar tətbiq olunur.
- Exception `record`/`retry` siyahısında deyil.
- Bean Spring tərəfindən yaradılmayıb (`new` ilə yaradılıb).
</details>

<details>
<summary><b>25. Resilience4j-ni reaktiv (WebFlux) kodda necə istifadə etmək olar?</b></summary>

Resilience4j `Mono` və `Flux` üçün operatorlar verir: `.transformDeferred(CircuitBreakerOperator.of(cb))`, `RetryOperator`, `RateLimiterOperator`, `BulkheadOperator`. Annotasiyalar da reaktiv qaytarma tiplərini tanıyır. Bloklamamaq vacibdir: reaktiv kodda `ThreadPoolBulkhead` əvəzinə semaphore bulkhead, timeout üçün isə Reactor-un `.timeout()` operatoru istifadə olunur.
</details>

<details>
<summary><b>26. Spring Cloud Circuit Breaker nədir?</b></summary>

Circuit breaker üçün abstraksiya qatıdır: kod `CircuitBreakerFactory.create("x").run(supplier, fallback)` ilə yazılır, altda isə Resilience4j (və ya başqa implementasiya) işləyir. Kitabxananı dəyişmək asanlaşır, amma bəzi imkanlar (ətraflı konfiqurasiya, bəzi modullar) abstraksiyadan kənarda qalır. Spring Cloud Gateway-də route səviyyəsində circuit breaker filtri də buna əsaslanır.
</details>

<details>
<summary><b>27. Spring Framework 7-nin <code>@Retryable</code>-ı ilə Resilience4j arasında fərq nədir?</b></summary>

Spring Framework 7 core-a `@Retryable` (backoff, jitter, timeout ilə) və `@ConcurrencyLimit` əlavə edib (`@EnableResilientMethods`). Bu, əlavə asılılıq olmadan sadə retry və paralellik limiti verir. Resilience4j-də isə circuit breaker, rate limiter, time limiter, bulkhead, zəngin metrikalar və event-lər var. Yalnız retry lazımdırsa Spring-in öz annotasiyası kifayətdir; asılılığın tam qorunması üçün Resilience4j istifadə olunur.
</details>

### Arxitektura

<details>
<summary><b>28. Resilience-i service mesh (Istio, Envoy) ilə etmək, yoxsa tətbiqdə?</b></summary>

- **Service mesh** retry, timeout və outlier detection-ı (circuit breaker-in analoqu) infrastrukturda, dildən asılı olmayaraq, kod dəyişmədən verir.
- **Tətbiq səviyyəsi** (Resilience4j) isə biznes kontekstini bilir: hansı xəta biznes xətasıdır, hansı fallback məqbuldur, hansı əməliyyat idempotentdir.

Praktikada ikisi birləşdirilir: timeout və sadə retry mesh-də, fallback və biznesə uyğun qaydalar tətbiqdə. Eyni retry-ın iki yerdə (mesh + tətbiq) konfiqurasiya olunmaması vacibdir: yoxsa retry storm yaranır.
</details>

<details>
<summary><b>29. Deadline (timeout budget) propagation nədir?</b></summary>

Zəncirin əvvəlində sorğunun ümumi vaxt büdcəsi təyin olunur (məs. 2 saniyə) və hər növbəti çağırışa **qalan** vaxt ötürülür. Nəticədə daxili servis client-in artıq imtina etdiyi sorğu üzərində işləməyə davam etmir. gRPC-də deadline protokolun daxili hissəsidir (13-cü fəsil); HTTP-də isə header ilə ötürülür və hər servis öz timeout-unu qalan büdcəyə görə kəsir.
</details>

<details>
<summary><b>30. Resilience konfiqurasiyasını necə test etmək olar?</b></summary>

- **İnteqrasiya testləri** asılılığı idarə olunan simulyatorla əvəz edir (bu modulda `PartnerSimulator`, və ya WireMock, Toxiproxy): yavaşlıq, xəta, "flaky" davranış yaradılır, sonra circuit-in açılması, fail-fast, half-open-dan bərpa, retry sayı və fallback yoxlanılır (bu modulun 7 testi).
- **Chaos engineering** (Chaos Monkey, Litmus, fault injection) production-a yaxın mühitdə real xətaları süni şəkildə yaradır.

Test olunmayan resilience konfiqurasiyası, çox güman ki, gözlənildiyi kimi işləmir.
</details>

---

[← 21. Redis](21-redis.md) · [Mündəricat](README.md) · Növbəti: [23. Spring Security və JWT →](23-security-jwt.md)
