# 8. `Mono` və `Flux`: gələcəyin təsviri

[← 7. Event loop: WebFlux adi request-dən nə ilə fərqlənir](07-event-loop.md) · [Mündəricat](README.md) · Növbəti: [9. Paralel çağırışlar: `Mono.zip` →](09-paralel.md)

**Hissə II: WebFlux** · **Kod:** [`webflux-streaming`](../webflux-streaming)

---

## Həyatdan analogiya

Resept və bişmiş yemək fərqli şeylərdir. Resept "un götür, qarışdır, 20 dəqiqə bişir" deyir, amma resepti yazanda heç nə bişmir. Kimsə resepti **işə salanda** (mətbəxə verəndə) addımlar icra olunur.

`Mono` və `Flux` reseptdir, nəticə deyil. Onları yazanda heç nə baş vermir, **abunə olanda** (subscribe) iş başlayır.

## İki tip

| | `Mono<T>` | `Flux<T>` |
|---|---|---|
| Neçə element | 0 və ya 1 | 0-dan sonsuza qədər |
| Qarşılığı | `Optional`/`CompletableFuture`, amma tənbəl | `Stream`, amma asinxron |
| Nümunə | `Mono<Order> findById(id)` | `Flux<Order> findAll()` |

## Tənbəllik: yazmaq ≠ işə salmaq

```java
Mono<String> user = services.user("42");   // heç nə olmadı, sadəcə resept
```

WebFlux controller-dən qaytarılan `Mono`/`Flux`-a özü abunə olur, cavabı da elementlər gəldikcə yazır. Siz heç vaxt `subscribe()` və ya `block()` çağırmırsınız.

## Əsas operatorlar: bu layihədən

**`map`: elementi çevir**

```java
Mono.delay(Duration.ofMillis(300))
    .map(tick -> new CallResult("reactive", handler, Thread.currentThread().getName()));
```

**`flatMap`: növbəti asinxron addım**

```java
services.user(id)
    .flatMap(user -> services.orders(id)           // user gələndən sonra orders-i çağır
        .map(orders -> new Pair(user, orders)));
```

`map` sinxron çevirmə üçündür (`T -> R`). `flatMap` isə nəticəsi özü də `Mono`/`Flux` olan addım üçündür (`T -> Mono<R>`).

**`zip`: paralel gözlə, birləşdir** (növbəti fəsil)

```java
Mono.zip(services.user(id), services.orders(id), services.recommendations(id))
```

**`delayElements`: elementlər arasında fasilə**

```java
Flux.fromIterable(tokens).delayElements(Duration.ofMillis(60))
```

**`concatMap`: ardıcıl, bir-bir**

```java
Flux.range(0, pages).concatMap(page -> loadPage(page))   // səhifələr sıra ilə
```

**`doOnNext`, `doOnCancel`, `doOnComplete`: yan təsir (log, sayğac)**

```java
.doOnNext(o -> rowsSent.incrementAndGet())
.doOnCancel(() -> log.info("Client cancelled"))
```

**`elapsed`: ölçmə**

```java
.elapsed()                                        // Mono<Tuple2<Long, T>>: (ms, dəyər)
.map(t -> new DashboardResponse("parallel", t.getT1(), t.getT2()));
```

## Thread-lər: kim işlədir?

Reaktiv kodda "bu sətir hansı thread-də işləyir?" sualının cavabı həmişə aydın olmur:

```java
public Mono<CallResult> reactive() {
    String handler = Thread.currentThread().getName();      // reactor-http-epoll-2
    return Mono.delay(Duration.ofMillis(300))
            .map(t -> Thread.currentThread().getName());    // parallel-3 (taymer thread-i)
}
```

Controller event loop-da çağırılır, `map` isə taymerin atəşləndiyi thread-də işləyir. Demo cavabında `handlerThread` və `completionThread` məhz bunu göstərir. Nəticə: reaktiv kodda `ThreadLocal`-a (məsələn MDC, security context) güvənmək olmaz. Reactor bunun üçün `Context` təqdim edir.

## Debug niyə çətindir

Adi kodda stack trace sizə "kim kimi çağırdı" deyir. Reaktiv kodda isə resept bir yerdə yazılıb, başqa yerdə, başqa thread-də icra olunur. Stack trace Reactor-un daxili sinifləri ilə dolu olur. Kömək edən vasitələr:
- `Hooks.onOperatorDebug()`: yavaşdır, yalnız dev üçün;
- `.checkpoint("orders stream")`: problemli yerə nişan qoymaq;
- `.log()`: hər siqnalı loglamaq.

## Yadda saxla

- `Mono` 0 və ya 1, `Flux` 0 və ya çox element. Hər ikisi tənbəl reseptdir.
- Çevirmə üçün `map`, asinxron növbəti addım üçün `flatMap`, paralel üçün `zip`.
- Controller-də `block()` çağırma, `Mono`/`Flux` qaytar.
- Thread dəyişə bilər, ona görə `ThreadLocal`-a güvənmə.

## Tapşırıq

1. `DashboardController`-də `sequential` metoduna `.log()` əlavə edin və loglarda siqnalların (`onSubscribe`, `request`, `onNext`, `onComplete`) sırasını izləyin.
2. `curl localhost:8081/api/compare/reactive` cavabında `handlerThread` və `completionThread` fərqlidirmi? `offloaded` üçün necə?

---

[← 7. Event loop: WebFlux adi request-dən nə ilə fərqlənir](07-event-loop.md) · [Mündəricat](README.md) · Növbəti: [9. Paralel çağırışlar: `Mono.zip` →](09-paralel.md)
