# Müsahibə sualları: II hissə, WebFlux və reaktiv proqramlaşdırma

[Mündəricat](README.md) · Fəsillər: [7](07-event-loop.md) · [8](08-mono-flux.md) · [9](09-paralel.md) · [10](10-reaktiv-streaming.md) · [11](11-backpressure.md)

Reaktiv proqramlaşdırma, Project Reactor və Spring WebFlux üzrə ən çox verilən 30 sual. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın.

---

### Reaktiv proqramlaşdırmanın əsasları

<details>
<summary><b>1. Reaktiv proqramlaşdırma nədir?</b></summary>

Məlumat axınları və dəyişikliklərin yayılması üzərində qurulmuş, **asinxron və non-blocking** proqramlaşdırma modelidir. Kod "bu dəyər gələndə bunu et" şəklində, deklarativ operator zəncirləri ilə yazılır. Thread nəticəni gözləyərkən bloklanmır, nəticə hazır olanda iş davam edir. Əsas xüsusiyyətləri: asinxronluq, axın kimi düşünmək, və **backpressure** (istehlakçı nə qədər qəbul edə biləcəyini deyir).
</details>

<details>
<summary><b>2. Reactive Streams spesifikasiyası nədir?</b></summary>

JVM üçün asinxron axınların backpressure ilə standart interfeysləridir (Java 9-dan `java.util.concurrent.Flow`-da da var):

- `Publisher<T>`: məlumat verir; `subscribe(Subscriber)`.
- `Subscriber<T>`: `onSubscribe`, `onNext`, `onError`, `onComplete`.
- `Subscription`: `request(n)`, `cancel()`.
- `Processor<T,R>`: həm subscriber, həm publisher.

Açar qayda budur: publisher subscriber-in `request(n)` ilə istədiyindən **çox** element göndərə bilməz. Project Reactor, RxJava və Akka Streams bu spesifikasiyanı həyata keçirir.
</details>

<details>
<summary><b>3. <code>Mono</code> ilə <code>Flux</code> arasında fərq nədir?</b></summary>

- `Mono<T>`: 0 və ya 1 element, sonra tamamlanma və ya xəta. Tək nəticə üçündür (`findById`, HTTP cavabı).
- `Flux<T>`: 0..N (sonsuz da ola bilər) element. Siyahılar və axınlar üçündür (SSE, NDJSON, Kafka mesajları).

Hər ikisi `Publisher`-dir. `Mono<List<T>>` "hamısı birdən", `Flux<T>` isə "gəldikcə" deməkdir. Ətraflı: [8-ci fəsil](08-mono-flux.md).
</details>

<details>
<summary><b>4. "Subscribe etməyincə heç nə baş vermir" nə deməkdir?</b></summary>

`Mono` və `Flux` əksər hallarda **tənbəldir** (lazy): operator zənciri yalnız bir **reseptdir**. İcra `subscribe()` olanda başlayır (WebFlux-da bunu framework özü edir). Nəticə:

- metodun içində yaradılıb qaytarılmayan `Mono` heç vaxt icra olunmur (məs. unudulmuş `repository.save(...)`);
- iki dəfə subscribe etmək işi iki dəfə görür (iki HTTP sorğu).
</details>

<details>
<summary><b>5. Cold və hot publisher arasında fərq nədir?</b></summary>

- **Cold:** hər subscriber üçün məlumatı sıfırdan yaradır (HTTP çağırışı, DB sorğusu): iki subscriber iki sorğu deməkdir.
- **Hot:** məlumat subscriber-lərdən asılı olmayaraq axır (qiymət tikeri, `Sinks.many().multicast()`): sonradan qoşulan yalnız ondan sonrakıları görür.

Cold-u hot-a `share()`, `publish().refCount()`, `cache()` və `replay()` çevirir.
</details>

<details>
<summary><b>6. <code>Mono.just()</code> ilə <code>Mono.defer()</code> / <code>fromCallable()</code> arasında fərq nədir?</b></summary>

`Mono.just(x)` dəyəri **dərhal**, yığılma anında hesablayır: `Mono.just(repository.findSlow())`-də sorğu subscribe-dan əvvəl, hətta heç subscribe olmasa belə, icra olunur. `Mono.fromCallable(() -> ...)` və `Mono.defer(() -> ...)` isə hər subscribe-da, **tənbəl** icra edir. Bloklayan və ya bahalı iş heç vaxt `just`-a qoyulmur.
</details>

### Operatorlar

<details>
<summary><b>7. <code>map</code> ilə <code>flatMap</code> arasında fərq nədir?</b></summary>

- `map` sinxron çevrilmədir: `T → R` (DTO-ya çevirmək).
- `flatMap` asinxron çevrilmədir: `T → Publisher<R>` (hər element üçün HTTP və ya DB çağırışı), və nəticələri bir axına birləşdirir.

`map` içində `Mono` qaytarsanız, `Mono<Mono<R>>` alınır: səhv budur.
</details>

<details>
<summary><b>8. <code>flatMap</code>, <code>concatMap</code> və <code>switchMap</code> arasında fərq nədir?</b></summary>

- `flatMap`: daxili publisher-lərə **paralel** subscribe edir (concurrency limiti ilə), nəticələr **qarışıq sırada** gəlir.
- `concatMap`: bir-bir, **ardıcıl**; sıra qorunur, amma yavaşdır.
- `flatMapSequential`: paralel işləyir, amma nəticələri orijinal sırada verir.
- `switchMap`: yeni element gələndə əvvəlkinin daxili publisher-ini **ləğv edir** (axtarış sahəsində yazarkən autocomplete).
</details>

<details>
<summary><b>9. <code>zip</code>, <code>merge</code> və <code>concat</code> arasında fərq nədir?</b></summary>

- `Mono.zip(a, b, c)`: hamısını **paralel** başladır və hamısı bitəndə nəticələri birləşdirir. Birinin xətası hamısını ləğv edir. Üç servisi paralel çağırmaq üçündür ([9-cu fəsil](09-paralel.md): 1200 ms əvəzinə 500 ms).
- `Flux.merge`: bir neçə axını paralel birləşdirir, elementlər gəldikcə qarışıq gəlir.
- `Flux.concat`: birincini sonuna qədər, sonra ikincini (ardıcıl) oxuyur.
</details>

<details>
<summary><b>10. Reaktiv zəncirdə xətaları necə idarə etmək olar?</b></summary>

Xəta siqnalı (`onError`) axını dayandırır və aşağı axır. Operatorlar:

- `onErrorResume(e -> fallbackPublisher)`: alternativ axın;
- `onErrorReturn(value)`: default dəyər;
- `onErrorMap(e -> new MyException(e))`: çevirmə;
- `doOnError`: loglama;
- `retry(n)` / `retryWhen(Retry.backoff(3, Duration.ofMillis(200)))`: yenidən subscribe.

`try/catch` işləmir, çünki xəta başqa thread-də və sonra baş verir.
</details>

<details>
<summary><b>11. Reaktiv zəncirdə timeout necə qoyulur?</b></summary>

`.timeout(Duration.ofSeconds(2))`: müddət ərzində element və ya tamamlanma gəlməsə, `TimeoutException` siqnalı atılır və upstream **ləğv olunur** (HTTP çağırışı kəsilir). Fallback ilə birləşdirilə bilər: `.timeout(d, fallbackMono)`. `WebClient`-də bundan əlavə bağlantı və cavab timeout-ları (`responseTimeout`) konfiqurasiya olunur.
</details>

### Thread-lər və scheduler-lər

<details>
<summary><b>12. WebFlux-un thread modeli necədir? Event loop nədir?</b></summary>

Netty az sayda **event loop thread-i** istifadə edir (adətən CPU nüvəsi qədər). Hər thread çoxlu bağlantıya xidmət edir: I/O hazır olanda callback işləyir, gözləmə zamanı isə başqa işlər görür. Thread heç vaxt bloklanmamalıdır. Çoxlu eyni vaxtlı bağlantı az thread ilə idarə olunur. Ətraflı: [7-ci fəsil](07-event-loop.md).
</details>

<details>
<summary><b>13. WebFlux-da bloklayan kod (JDBC, <code>Thread.sleep</code>) çağırsanız nə olar?</b></summary>

Event loop thread-i bloklanır və **həmin thread-ə düşən bütün digər sorğular** gözləyir. Sistem MVC-dən də pis işləyir, çünki thread sayı çox azdır. Modulda ölçülüb: 100 paralel sorğu bloklayan kodla ~8000 ms, reaktiv kodla ~380 ms ([7-ci fəsil](07-event-loop.md)). R2DBC modulunda isə JDBC event loop-da 5.3 s, R2DBC 1.4 s çəkdi.
</details>

<details>
<summary><b>14. Bloklayan kitabxananı WebFlux-da necə istifadə etmək olar?</b></summary>

Çağırışı bloklama üçün nəzərdə tutulmuş scheduler-ə köçürmək: `Mono.fromCallable(() -> blockingCall()).subscribeOn(Schedulers.boundedElastic())`. Event loop azad qalır, amma hər gözləyən çağırış `boundedElastic` pool-unda bir thread tutur (default limit: 10 × nüvə, sonra növbə). Bu, "yamaq"dır. Stack-in əksəriyyəti bloklayandırsa, Spring MVC + virtual thread-lər daha düzgün seçimdir.
</details>

<details>
<summary><b>15. <code>publishOn</code> ilə <code>subscribeOn</code> arasında fərq nədir?</b></summary>

- `subscribeOn(scheduler)`: **abunə olmanın və mənbənin** hansı thread-də işləyəcəyini təyin edir. Zəncirin harasında yazılmasından asılı olmayaraq mənbəyə təsir edir; birinci `subscribeOn` qalib gəlir.
- `publishOn(scheduler)`: **özündən sonrakı** operatorların thread-ini dəyişir. Zəncirin ortasında thread keçidi üçündür.

Tipik istifadə: bloklayan mənbə üçün `subscribeOn(boundedElastic())`, ağır CPU emalı üçün `publishOn(parallel())`.
</details>

<details>
<summary><b>16. Reactor-un scheduler-ləri hansılardır?</b></summary>

- `Schedulers.parallel()`: CPU-bound iş üçün, nüvə sayı qədər thread; bloklamaq olmaz.
- `Schedulers.boundedElastic()`: bloklayan I/O üçün, məhdud və genişlənən pool.
- `Schedulers.single()`: bir thread.
- `Schedulers.immediate()`: cari thread.
- `Schedulers.fromExecutorService(...)`: öz pool-unuz.

Java 21+-da `boundedElastic` virtual thread-lərlə də konfiqurasiya oluna bilər.
</details>

<details>
<summary><b>17. Reaktiv kodda <code>ThreadLocal</code> niyə işləmir və əvəzində nə istifadə olunur?</b></summary>

Bir sorğunun emalı thread-dən thread-ə keçə bilər, ona görə `ThreadLocal` (MDC, security context, trace id) itir. Reactor-un öz **Context**-i var: abunə zəncirinə bağlı açar-dəyər xəritəsidir (`contextWrite`, `Mono.deferContextual`). Spring Security (`ReactiveSecurityContextHolder`), tranzaksiyalar (R2DBC) və Micrometer tracing bunu istifadə edir. `Hooks.enableAutomaticContextPropagation()` (və ya `spring.reactor.context-propagation=auto`) `ThreadLocal`-ları Context ilə avtomatik sinxronlaşdırır.
</details>

### Backpressure və ləğv ([11-ci fəsil](11-backpressure.md))

<details>
<summary><b>18. Backpressure nədir və Reactor-da necə işləyir?</b></summary>

İstehlakçının istehsalçıya "yalnız N element göndər" deməsidir (`request(n)`). Beləliklə, sürətli mənbə yavaş istehlakçını boğmur və yaddaş dolmur. Operatorlar arasında avtomatik ötürülür: `flatMap` concurrency qədər, `publishOn` və `limitRate` isə prefetch qədər istəyir. Modulda ölçülüb: 100 000 sətirlik mənbədən 150-si oxunanda cəmi 2 səhifə sorğulandı ([11-ci fəsil](11-backpressure.md)). R2DBC modulunda isə 100 sətir istənəndə bazadan 500-dən az oxundu.
</details>

<details>
<summary><b>19. Backpressure-i dəstəkləməyən mənbə (məs. hadisə dinləyicisi) üçün hansı strategiyalar var?</b></summary>

- `onBackpressureBuffer(max)`: yığmaq (limitlə, yoxsa yaddaş dolur);
- `onBackpressureDrop()`: yeniləri atmaq;
- `onBackpressureLatest()`: yalnız sonuncunu saxlamaq (qiymət, status kimi yeniləmələr üçün ideal);
- `onBackpressureError()`: xəta vermək.

`Flux.create` və `Sinks` ilə yaradılan mənbələrdə strategiya seçilir. Hansının düzgün olduğu biznes qərarıdır.
</details>

<details>
<summary><b>20. Client bağlantını kəsəndə reaktiv zəncirdə nə baş verir?</b></summary>

Netty bağlantının bağlandığını görür və subscription-a `cancel()` siqnalı göndərir. Ləğv yuxarıya, mənbəyə qədər yayılır: DB sorğusu və ya HTTP çağırışı dayanır, `interval` isə dayanır. MVC-dən fərqli olaraq bu avtomatikdir, amma yalnız mənbə ləğvi dəstəkləyirsə işləyir. `doOnCancel` ilə resursları təmizləmək olar.
</details>

<details>
<summary><b>21. <code>limitRate</code> nə edir?</b></summary>

Aşağı axının tələbini hissələrə bölür: `limitRate(100)` yuxarıdan 100-lük dəstələrlə istəyir və 75%-i emal olunanda növbəti dəstəni istəyir (replenish). Beləliklə, `request(Long.MAX_VALUE)` (sonsuz tələb) göndərən subscriber belə mənbəni idarə olunan hissələrlə oxuyur. Bu, xüsusən səhifə-səhifə oxunan mənbələr üçün faydalıdır.
</details>

### Spring WebFlux

<details>
<summary><b>22. WebFlux-da annotasiyalı controller və functional endpoint arasında fərq nədir?</b></summary>

- **Annotasiyalı** (`@RestController`, `@GetMapping`): Spring MVC ilə eyni stil, sadəcə `Mono`/`Flux` qaytarır.
- **Functional** (`RouterFunction` + `HandlerFunction`): route-lar kodla, lambda-larla təyin olunur; açıqdır, test etmək asandır və refleksiya azdır.

İkisi də eyni reaktiv nüvədə işləyir; seçim zövq və komanda məsələsidir.
</details>

<details>
<summary><b>23. WebFlux controller-i <code>Flux</code> qaytaranda cavab necə yazılır?</b></summary>

Content type-dan asılıdır:

- `application/json`: `Flux` **yığılır** və JSON massivi kimi bir dəfəyə yazılır;
- `text/event-stream`: SSE, hər element gəldikcə;
- `application/x-ndjson`: hər element ayrı sətir, gəldikcə.

Streaming istəyirsinizsə, `produces`-də streaming media type göstərilməlidir. Ətraflı: [10-cu fəsil](10-reaktiv-streaming.md).
</details>

<details>
<summary><b>24. <code>WebClient</code> nədir və <code>RestClient</code>-dən nə ilə fərqlənir?</b></summary>

`WebClient` non-blocking, reaktiv HTTP client-dir: `Mono`/`Flux` qaytarır, streaming cavabları oxuya bilir və backpressure ilə işləyir. `RestClient` isə sinxron, bloklayan client-dir. WebFlux tətbiqində `WebClient` istifadə olunur; `RestClient` event loop-u bloklayar. Spring Boot 4-də `WebClient.Builder` ayrıca `spring-boot-starter-webclient` starter-i ilə gəlir.
</details>

<details>
<summary><b>25. WebFlux-da <code>.block()</code> çağırmaq niyə səhvdir?</b></summary>

`block()` cari thread-i nəticə gələnə qədər dayandırır. Event loop-da bu, bütün thread-i bloklayır; Reactor isə bəzi hallarda "block() is not supported in thread reactor-http-*" xətası atır. Ən pisi, bu, deadlock-a səbəb ola bilər: nəticəni gətirməli olan callback eyni, bloklanmış thread-də işləməlidir. `block()` yalnız testlərdə və ya reaktiv olmayan sərhəddə (məs. `main`) məqbuldur.
</details>

<details>
<summary><b>26. Reaktiv kodu necə test etmək olar?</b></summary>

`StepVerifier` ilə: `StepVerifier.create(flux).expectNext(a, b).expectComplete().verify()`. Backpressure `thenRequest(n)` ilə, ləğv `thenCancel()` ilə yoxlanılır, zaman əsaslı operatorlar isə virtual zamanla (`withVirtualTime`, `thenAwait`) saniyələr gözləmədən test olunur. HTTP səviyyəsində `WebTestClient` istifadə olunur. Bloklayan çağırışları testdə tutmaq üçün **BlockHound** işlədilir.
</details>

<details>
<summary><b>27. Reaktiv kodu necə debug etmək olar?</b></summary>

Adi stack trace operatorların daxilini göstərir, sizin kodunuzu yox. Kömək edənlər:

- `.checkpoint("təsvir")`: zəncirə işarə qoyur;
- `.log()`: siqnalları loglayır;
- `Hooks.onOperatorDebug()`: bütün zəncirlər üçün montaj izi; bahalıdır, yalnız dev-də;
- `ReactorDebugAgent` (reactor-tools): production-da ucuz montaj izi.

Kiçik, adlandırılmış metodlar zənciri oxunaqlı edir.
</details>

<details>
<summary><b>28. <code>Sinks</code> nədir?</b></summary>

İmperativ koddan (callback, hadisə dinləyicisi) reaktiv axına programatik olaraq element göndərmək üçün API-dir. Növləri:

- `Sinks.one()`: bir dəyər;
- `Sinks.many().unicast()`: bir subscriber;
- `Sinks.many().multicast()`: çox subscriber, yalnız yeniləri görür;
- `Sinks.many().replay()`: son N elementi təkrarlayır.

`tryEmitNext` nəticəni qaytarır (məs. subscriber yoxdur, overflow). Paralel emit-lərdə isə `emitNext` və retry handler ilə istifadə olunur. SSE ilə bir hadisəni bir neçə client-ə yaymaq üçün tipik seçimdir.
</details>

<details>
<summary><b>29. WebFlux-da tranzaksiyalar və bazalar necə işləyir?</b></summary>

JDBC/JPA bloklayandır və WebFlux-da (offload etmədən) istifadə olunmamalıdır. Reaktiv bazaya giriş üçün **R2DBC** (relyasiyalı) və reaktiv driver-lər (MongoDB, Cassandra, Redis/Lettuce) istifadə olunur. `@Transactional` reaktiv metodlarda da işləyir (`R2dbcTransactionManager`); tranzaksiya Reactor Context-də saxlanılır. Proqramatik variant `TransactionalOperator`-dur. Ətraflı: [r2dbc modulu](../r2dbc/README.md).
</details>

<details>
<summary><b>30. WebFlux-u nə vaxt seçmək lazımdır, nə vaxt yox?</b></summary>

**Seçin:**

- tam reaktiv stack (R2DBC, reaktiv client-lər);
- çoxlu uzunömürlü bağlantı (SSE, WebSocket, streaming);
- API gateway və ya BFF (çoxlu servisi paralel çağırıb birləşdirmək);
- axınların emalı və backpressure.

**Seçməyin:**

- JPA və bloklayan kitabxanalardan imtina edə bilmirsinizsə;
- komanda reaktivi bilmirsə;
- adi CRUD üçün, çünki Java 21-dən sonra Spring MVC + virtual thread-lər daha sadə koddur və eyni miqyası verir.

Ətraflı: [18-ci fəsil](18-secim.md).
</details>

---

[← I hissə, Spring MVC sualları](musahibe-1-spring-mvc.md) · [Mündəricat](README.md) · Növbəti: [III hissə, gRPC sualları →](musahibe-3-grpc.md)
