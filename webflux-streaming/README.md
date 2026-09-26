# WebFlux: adi request-lərdən fərqi

[← Əsas səhifə](../README.md) · Müqayisə üçün: [Spring MVC modulu](../mvc-streaming/README.md)

Demo səhifə: **http://localhost:8081**

## İki model

**Adi (servlet) model: Spring MVC, Tomcat.** Hər request-ə bir thread verilir. Kod DB-dən və ya başqa servisdən cavab gözləyəndə həmin thread də gözləyir və heç nə etmir.

```
request 1 ──► thread-1: [iş][ gözləyir ......... ][iş] ──► cavab
request 2 ──► thread-2: [iş][ gözləyir ......... ][iş] ──► cavab
...
request 201 ─► boş thread yoxdur, növbədə gözləyir
```

**Reaktiv model: WebFlux, Netty.** Hər CPU nüvəsinə bir "event loop" thread-i düşür (4 nüvə = 4 thread). Thread gözləmir: sorğunu göndərir, növbəti request-ə keçir, cavab gələndə həmin hadisəni emal edir.

```
event-loop-1: [req1 iş][req2 iş][req3 iş][req1 cavab gəldi → iş][req2 cavab gəldi → iş]...
```

Bu modelin bir sərt qaydası var: **event-loop thread-i heç vaxt bloklanmamalıdır.** O thread gözləyəndə ona düşən bütün request-lər də gözləyir.

| | Spring MVC (servlet) | WebFlux (reaktiv) |
|---|---|---|
| Server | Tomcat | Netty |
| Thread modeli | Hər request-ə bir thread (default 200) | Az sayda event-loop thread (nüvə sayı qədər) |
| Gözləmə | Thread bloklanır | Thread azad olur, cavab gələndə davam edir |
| Controller qaytarır | `Order`, `List<Order>` | `Mono<Order>`, `Flux<Order>` |
| Başqa servisə müraciət | `RestClient` (bloklayan) | `WebClient` (reaktiv) |
| Verilənlər bazası | JDBC, JPA | R2DBC, reaktiv Mongo/Redis |
| Streaming | `SseEmitter`, `ResponseBodyEmitter` + öz thread-in | Sadəcə `Flux` qaytarırsan |
| Backpressure | Yoxdur | Var: istehsalçı istehlakçının sürətinə uyğunlaşır |
| Debug, stack trace | Sadə | Çətin: kod callback-lərə bölünür |

---

## Nümunə 1: Thread modeli, 100 paralel sorğu

[`CompareController`](src/main/java/io/github/gshahrza/streaming/webflux/compare/CompareController.java)

Eyni iş, yəni 300 ms gözləmə, üç cür yazılıb:

```java
// 1. SƏHV: event-loop thread-i 300 ms yatır
@GetMapping("/api/compare/blocking")
public CallResult blocking() throws InterruptedException {
    Thread.sleep(300);
    return ...;
}

// 2. DÜZGÜN: gözləmə bir taymerdir, thread dərhal azad olur
@GetMapping("/api/compare/reactive")
public Mono<CallResult> reactive() {
    return Mono.delay(Duration.ofMillis(300)).map(t -> ...);
}

// 3. Bloklayan kodu dəyişmək mümkün deyilsə: onu ayrıca thread pool-a köçür
@GetMapping("/api/compare/offloaded")
public Mono<CallResult> offloaded() {
    return Mono.fromCallable(() -> { Thread.sleep(300); return ...; })
               .subscribeOn(Schedulers.boundedElastic());
}
```

`/api/compare/load?mode=...` endpoint-i hər variantı `WebClient` ilə 100 paralel sorğuya məruz qoyur. Sorğuları brauzer yox, server göndərir, çünki brauzer bir hosta cəmi ~6 əlaqə açır. Nəticələr 4 nüvəli maşında, Docker-də ölçülüb:

| Variant | 100 sorğunun ümumi vaxtı | Niyə |
|---|---|---|
| Bloklayan | **~8000 ms** | 4 thread × hər biri ardıcıl 25 × 300 ms |
| Reaktiv | **~380 ms** | 100-ü də eyni anda gözləyir, thread tutulmur |
| `boundedElastic`-ə köçürülmüş | ~940 ms | Bu pool-da nüvə × 10 = 40 thread var |

Eyni bloklayan kod Spring MVC-də problem yaratmazdı: Tomcat-ın 200 thread-i 100 sorğunu paralel emal edərdi. Problem bloklamaqda deyil, **az sayda event-loop thread-ini** bloklamaqdadır.

WebFlux-da gizli bloklayan kodlar bunlardır:
- JDBC və JPA/Hibernate;
- `RestTemplate`, `RestClient`;
- fayl IO;
- `Thread.sleep`;
- `Mono.block()`, `Flux.blockLast()`;
- `synchronized` blokda uzun gözləmə.

Belə kodu tapmaq üçün [BlockHound](https://github.com/reactor/BlockHound) aləti var.

## Nümunə 2: Üç servisə paralel müraciət

[`DashboardController`](src/main/java/io/github/gshahrza/streaming/webflux/dashboard/DashboardController.java)

Səhifə üç servisdən məlumat alır: istifadəçi (300 ms), sifarişlər (500 ms), tövsiyələr (400 ms).

```java
// Ardıcıl: 300 + 500 + 400 = ~1200 ms
services.user(id).flatMap(user ->
    services.orders(id).flatMap(orders ->
        services.recommendations(id).map(recs -> new Dashboard(user, orders, recs))));

// Paralel: ən yavaşı qədər = ~500 ms
Mono.zip(services.user(id), services.orders(id), services.recommendations(id))
    .map(t -> new Dashboard(t.getT1(), t.getT2(), t.getT3()));
```

Adi kodda paralellik üçün `CompletableFuture` və thread pool lazımdır. `Mono.zip` isə əlavə thread yaratmır: üç sorğu göndərilir və eyni event loop hər üçünün cavabını gözləyir.

## Nümunə 3: Chat, SSE

[`ChatController`](src/main/java/io/github/gshahrza/streaming/webflux/chat/ChatController.java)

MVC ilə müqayisə edin. Nəticə eynidir, amma kodun həcmi və məsuliyyət fərqlidir:

| Spring MVC ([ChatController](../mvc-streaming/src/main/java/io/github/gshahrza/streaming/mvc/chat/ChatController.java)) | WebFlux |
|---|---|
| `SseEmitter` yarat | `Flux` qaytar |
| Executor-da thread aç | Lazım deyil |
| `for` + `emitter.send()` + `Thread.sleep()` | `Flux.fromIterable(tokens).delayElements(delay)` |
| `onCompletion/onError` bayrağı ilə "client getdimi?" yoxla | Client gedəndə Flux avtomatik ləğv olunur |
| `IOException`-u tut | Lazım deyil |

```java
@GetMapping(path = "/api/chat/stream", produces = TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<Map<String, Object>>> stream(@RequestParam String prompt) {
    return Flux.fromIterable(tokens(prompt))
            .delayElements(Duration.ofMillis(60))
            .map(token -> ServerSentEvent.builder(Map.of("text", token)).event("token").build())
            .concatWith(doneEvent);
}
```

## Nümunə 4: Eyni `Flux`, iki format; backpressure və ləğv

[`OrderController`](src/main/java/io/github/gshahrza/streaming/webflux/order/OrderController.java), [`OrderRepository`](src/main/java/io/github/gshahrza/streaming/webflux/order/OrderRepository.java)

```java
@GetMapping(path = "/api/orders", produces = {APPLICATION_NDJSON_VALUE, APPLICATION_JSON_VALUE})
public Flux<Order> orders(@RequestParam int count) {
    return repository.findAll(count);
}
```

Bir metod var, format isə `Accept` header-inə görə seçilir:

| `Accept` | Cavab | Brauzer nə vaxt istifadə edə bilər |
|---|---|---|
| `application/x-ndjson` | Hər `Order` ayrıca sətir | İlk sətir gələn kimi (~150 ms) |
| `application/json` | `[{...},{...},...]` massivi | Yalnız sonda, `]` gələndə (~3000 ms) |

Bir incəlik var: WebFlux JSON massivini də yaddaşa yığmadan, element-element yazır, ölçmədə ilk baytlar ~160 ms-də gəlir. Amma yarımçıq JSON massivi parse olunmur, ona görə `response.json()` sonuna qədər gözləyir. Hissə-hissə **istifadə** etmək üçün NDJSON və ya SSE lazımdır.

### Backpressure

`OrderRepository.findAll()` səhifələri tələb olunduqca "sorğulayır":

```java
return Flux.range(0, pages)
        .concatMap(page -> Mono.delay(pageDelay).thenMany(Flux.fromIterable(page(page))));
```

Istehlakçı 10 sətir istəyirsə, yalnız bir səhifə oxunur. Bunu test də yoxlayır:

```java
StepVerifier.create(repository.findAll(100_000), 10)   // yalnız 10 element istə
        .expectNextCount(10)
        .thenCancel()
        .verify();
assertThat(pagesQueried).isEqualTo(1);                  // 1000 səhifədən yalnız 1-i
```

### Ləğv (cancel)

Brauzer "Dayandır" düyməsi ilə `fetch`-i dayandıranda WebFlux Flux-u ləğv edir (`doOnCancel`) və qalan səhifələr heç vaxt oxunmur. Ölçmə: 100 000 sətirlik stream-dən 150 sətir oxunub əlaqə bağlandı və server 1000 səhifədən cəmi **2-sini** sorğuladı. Demo səhifə dayandırmadan sonra `/api/orders/stats`-dan server statistikasını göstərir.

---

## Nə vaxt WebFlux, nə vaxt Spring MVC?

**WebFlux uyğundur:**
- çoxlu eyni vaxtlı, uzun yaşayan əlaqələr olanda (SSE, WebSocket, streaming);
- servis əsasən başqa servisləri çağırıb nəticələri birləşdirəndə (API gateway, BFF);
- backpressure lazım olanda;
- bütün zəncir reaktiv olanda (R2DBC, reaktiv Mongo/Redis/Kafka).

**Spring MVC daha yaxşıdır:**
- JPA/JDBC və digər bloklayan kitabxanalardan istifadə edəndə;
- komanda reaktiv proqramlaşdırmaya öyrəşməyəndə, çünki öyrənmə əyrisi və debug çətinliyi var;
- adi CRUD servislərində.

**Java 21 virtual thread-ləri** (bax: [mvc-streaming](../mvc-streaming)) bloklayan kodda thread qıtlığı problemini böyük ölçüdə aradan qaldırır. Nəticədə "sırf performans üçün WebFlux" arqumenti zəifləyib. WebFlux-un güclü tərəfi indi daha çox **axınları birləşdirmək, backpressure və streaming**-dir.

## Spring Boot 4 qeydi

Boot 4-də auto-configuration modullara bölünüb. `WebClient.Builder` bean-i artıq `spring-boot-starter-webflux`-da yoxdur, onun üçün ayrıca `spring-boot-starter-webclient` lazımdır (bax: [build.gradle](build.gradle)).

## Testlər

[`WebfluxStreamingTest`](src/test/java/io/github/gshahrza/streaming/webflux/WebfluxStreamingTest.java) aşağıdakıları yoxlayır:
- `WebTestClient` ilə real server üzərində bloklayan kodun reaktiv koddan ən azı 3 dəfə yavaş olduğunu;
- `Mono.zip`-in paralelliyini;
- SSE hadisələrini;
- NDJSON-da ilk sətrin tez gəldiyini;
- `StepVerifier` ilə backpressure-u;
- client ləğvinin serveri də dayandırdığını.

```bash
./gradlew :webflux-streaming:test
```
