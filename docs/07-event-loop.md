# 7. Event loop: WebFlux adi request-dən nə ilə fərqlənir

[← 6. Production-da streaming: görünməyən tələlər](06-production.md) · [Mündəricat](README.md) · Növbəti: [8. `Mono` və `Flux`: gələcəyin təsviri →](08-mono-flux.md)

**Hissə II: WebFlux** · **Kod:** [`CompareController`](../webflux-streaming/src/main/java/io/github/gshahrza/streaming/webflux/compare/CompareController.java) · **Demo:** http://localhost:8081, 1-ci bölmə

---

## Həyatdan analogiya

İki restoran var.

**Birincidə** hər masaya bir ofisiant təyin olunur. Ofisiant sifarişi mətbəxə verir və yemək hazır olana qədər mətbəxin qapısında gözləyir. 200 ofisiant varsa, 200 masaya xidmət olunur. Ofisiantlar vaxtının çoxunu gözləməyə sərf edir.

**İkincidə** cəmi 4 ofisiant var, amma heç biri gözləmir. Sifarişi mətbəxə verir, növbəti masaya keçir. Mətbəx zəng vuranda ("3-cü masanın kababı hazırdır") hansı ofisiant boşdursa, o aparır. 4 nəfər yüzlərlə masaya çatdırır.

Birinci restoran Spring MVC-dir (Tomcat), ikincisi WebFlux-dur (Netty). İkinci restoranın bir sərt qaydası var: **ofisiant heç vaxt mətbəxin qapısında gözləməməlidir.** Biri gözləsə, onun masaları da gözləyir.

## Event loop nədir

Netty hər CPU nüvəsi üçün bir thread yaradır, 4 nüvədə 4 thread olur (`reactor-http-epoll-1..4`). Hər thread sonsuz bir dövr işlədir:

```
while (true) {
    hadisələr = əməliyyat sistemindən soruş("hansı socket-lərdə nə baş verdi?")
    for (hadisə : hadisələr) {
        uyğun callback-i qısa müddətə işlət   // bloklamadan!
    }
}
```

"Hadisə" yeni sorğunun gəlməsi, DB cavabının gəlməsi və ya taymerin bitməsi ola bilər. Thread heç vaxt "gözləmir", sadəcə növbəti hadisəyə keçir.

## Eksperiment: eyni 300 ms, üç cür

**1. Bloklayan kod: səhv**

```java
@GetMapping("/api/compare/blocking")
public CallResult blocking() throws InterruptedException {
    Thread.sleep(300);                       // event-loop thread-i 300 ms yatır
    return ...;
}
```

**2. Reaktiv kod: düzgün**

```java
@GetMapping("/api/compare/reactive")
public Mono<CallResult> reactive() {
    return Mono.delay(Duration.ofMillis(300))    // "300 ms sonra mənə xəbər ver"
            .map(tick -> ...);
}
```

`Mono.delay` taymer qurur və dərhal qayıdır. Thread növbəti sorğuya keçir. 300 ms sonra taymer hadisəsi gəlir və `map` işləyir.

**3. Bloklayan kodu köçürmək: kompromis**

```java
@GetMapping("/api/compare/offloaded")
public Mono<CallResult> offloaded() {
    return Mono.fromCallable(() -> { Thread.sleep(300); return ...; })
            .subscribeOn(Schedulers.boundedElastic());
}
```

`boundedElastic` bloklayan iş üçün nəzərdə tutulmuş thread pool-dur (nüvə × 10 thread). Bloklama orada baş verir, event loop azad qalır.

## Nəticə: 100 paralel sorğu

`/api/compare/load` endpoint-i `WebClient` ilə 100 paralel sorğu göndərir. Sorğuları brauzer göndərmir, çünki brauzer bir hosta cəmi ~6 əlaqə açır. 4 nüvəli maşında, Docker-də ölçülüb:

| Variant | Ümumi vaxt | Hesablama |
|---|---|---|
| Bloklayan | **~8000 ms** | 100 sorğu ÷ 4 thread × 300 ms ≈ 7500 ms |
| Reaktiv | **~380 ms** | 100-ü də eyni anda gözləyir |
| `boundedElastic` | ~940 ms | 100 ÷ 40 thread × 300 ms ≈ 750 ms, üstəgəl yüklənmə |

Bloklayan variantda 100 sorğu 4 thread-ə növbə ilə düşür. Reaktiv variantda isə 4 thread 100 taymer qurub boşalır.

## Eyni kod MVC-də niyə problem deyil?

Tomcat-ın 200 thread-i var, 100 bloklayan sorğu paralel gedir: ~300 ms. **Problem bloklamanın özündə deyil, az sayda event-loop thread-ini bloklamaqdadır.** WebFlux-a keçib köhnə bloklayan kodu saxlamaq MVC-dən **daha pis** nəticə verir.

## WebFlux-da gizli bloklayan kodlar

| Bloklayır | Reaktiv qarşılığı |
|---|---|
| JDBC, JPA/Hibernate | R2DBC, Spring Data R2DBC |
| `RestTemplate`, `RestClient` | `WebClient` |
| `Thread.sleep` | `Mono.delay`, `delayElements` |
| `mono.block()`, `flux.blockLast()` | `flatMap` ilə zəncirləmək |
| Fayl IO (`Files.readAllBytes`) | `DataBufferUtils`, və ya `boundedElastic` |
| Uzun `synchronized` blok | Kilidsiz strukturlar |

Belə kodu testlərdə avtomatik tapmaq üçün [BlockHound](https://github.com/reactor/BlockHound) aləti var.

## Yadda saxla

- MVC: çoxlu thread, hər biri gözləyə bilər. WebFlux: az thread, heç biri gözləməməlidir.
- Event loop-u bloklamaq WebFlux-u MVC-dən də yavaş edir.
- Bloklayan kod qaçılmazdırsa, `subscribeOn(Schedulers.boundedElastic())`.

## Tapşırıq

1. Demo-da gecikməni 1000 ms edib üç variantı yenidən işə salın. Rəqəmləri əvvəlcədən hesablayın, sonra yoxlayın.
2. `/api/compare/blocking` yüklənərkən başqa tab-da `/api/orders/stats`-ı açın. O da gecikirmi? Niyə?

---

[← 6. Production-da streaming: görünməyən tələlər](06-production.md) · [Mündəricat](README.md) · Növbəti: [8. `Mono` və `Flux`: gələcəyin təsviri →](08-mono-flux.md)
