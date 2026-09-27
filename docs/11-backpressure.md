# 11. Backpressure və ləğv

[← 10. Reaktiv streaming: `Flux` qaytarmaq kifayətdir](10-reaktiv-streaming.md) · [Mündəricat](README.md) · Növbəti: [12. gRPC nədir: müqavilə, protobuf, HTTP/2 →](12-grpc-nedir.md)

**Hissə II: WebFlux** · **Kod:** [`OrderRepository`](../webflux-streaming/src/main/java/io/github/gshahrza/streaming/webflux/order/OrderRepository.java), [`OrderController`](../webflux-streaming/src/main/java/io/github/gshahrza/streaming/webflux/order/OrderController.java), [`WebfluxStreamingTest`](../webflux-streaming/src/test/java/io/github/gshahrza/streaming/webflux/WebfluxStreamingTest.java) · **Demo:** 4-cü bölmə, "Dayandır" düyməsi

---

## Həyatdan analogiya

Konveyerdə qutuları qablaşdırırsınız. Konveyer sizin sürətinizdən asılı olmayaraq işləsə, qutular yerə tökülür. Yaxşı konveyerdə isə yanınızda bir düymə var: "növbəti 10 qutunu ver". Konveyer yalnız istədiyiniz qədər göndərir. Siz yavaşlasanız, o da yavaşlayır. Getsəniz, dayanır.

Bu düymə **backpressure**-dur: istehlakçı istehsalçıya nə qədər qəbul edə biləcəyini deyir.

## Reactive Streams müqaviləsi

`Flux` sadəcə "elementlər axını" deyil. Onun arxasında dörd siqnallı bir protokol var:

```
Subscriber                          Publisher
    │ ── subscribe() ──────────────►│
    │ ◄── onSubscribe(subscription) │
    │ ── request(10) ──────────────►│   "10 element göndər"
    │ ◄── onNext × 10 ──────────────│
    │ ── request(10) ──────────────►│   "daha 10"
    │ ◄── onNext × 10 ──────────────│
    │ ── cancel() ─────────────────►│   "bəsdir"
```

Publisher heç vaxt istəniləndən çox göndərmir. WebFlux-da bu `request` siqnalları birbaşa şəbəkəyə bağlıdır: client oxumursa, socket buferi dolur və Netty yeni element istəmir.

## Tələb üzrə oxuyan repository

```java
public Flux<Order> findAll(int count) {
    return Flux.range(0, pages)
            .concatMap(page -> Mono.delay(properties.orderPageDelay())
                    .doOnNext(t -> pagesQueried.incrementAndGet())
                    .thenMany(Flux.fromIterable(page(page, pageSize, count))));
}
```

- **`Flux.range(0, pages)`** səhifə nömrələridir.
- **`concatMap`** səhifələri **bir-bir** işləyir. Növbəti səhifə yalnız əvvəlkinin elementləri tükənib daha çox tələb olunanda başlayır.
- **`Mono.delay(...)`** "DB sorğusu"dur və `pagesQueried` sayğacı onu sayır.

## Test: "10 istədim, 1 səhifə oxundu"

```java
@Test
void backpressureQueriesOnlyThePagesThatAreRequested() {
    long before = repository.pagesQueried();

    StepVerifier.create(repository.findAll(100_000), 10)   // yalnız request(10)
            .expectNextCount(10)
            .thenCancel()
            .verify();

    assertThat(repository.pagesQueried() - before).isEqualTo(1);
}
```

`StepVerifier` Reactor-un test alətidir. Onun ikinci parametri ilkin tələbdir (`request(10)`). 100 000 sətirlik (1000 səhifəlik) axından yalnız 1 səhifə oxundu.

## Ləğv: client gedəndə

```java
return repository.findAll(count)
        .doOnNext(o -> rowsSent.incrementAndGet())
        .doOnComplete(completed::incrementAndGet)
        .doOnCancel(() -> {
            cancelled.incrementAndGet();
            log.info("Client cancelled the order stream; remaining pages will not be queried");
        });
```

Brauzer `fetch`-i dayandıranda (`AbortController.abort()`) TCP əlaqəsi bağlanır. Netty bunu görür, WebFlux isə `Flux`-a `cancel()` siqnalı göndərir. Siqnal zəncir boyu yuxarı, `concatMap`-ə və `Mono.delay`-ə qədər gedir və növbəti səhifə heç vaxt başlamır.

**Ölçmə:** 100 000 sətirlik stream-dən 150 sətir oxunub əlaqə bağlandı:

```
stats before: pagesQueried=40, cancelledStreams=0
stats after:  pagesQueried=42, cancelledStreams=1
```

Nəticədə 1000 səhifədən yalnız **2**-si oxundu. Demo səhifə "Dayandır" düyməsindən sonra `/api/orders/stats`-ı göstərir.

## MVC ilə müqayisə

| | Spring MVC (Hissə I) | WebFlux |
|---|---|---|
| Client yavaş oxuyur | `send()` bloklanır və thread gözləyir | `request` gəlmir, istehsal dayanır, thread tutulmur |
| Client gedir | `IOException` + əl ilə bayraq | `cancel` siqnalı, avtomatik |
| Kod | Əl ilə idarə | Operatorlar bunu daxildə edir |

## Yadda saxla

- Backpressure: istehlakçı `request(n)` ilə nə qədər qəbul edəcəyini deyir, istehsalçı ondan çox göndərmir.
- WebFlux-da şəbəkə sürəti avtomatik backpressure-a çevrilir.
- Client gedəndə `cancel` siqnalı mənbəyə qədər gedir. Mənbə tənbəl olmalıdır (`concatMap`, `Flux.range`), yoxsa artıq iş görülüb olur.
- Test üçün `StepVerifier.create(flux, n)` və `thenCancel()` istifadə olunur.

## Tapşırıq

1. `concatMap`-i `flatMap` ilə əvəz edin və testi işə salın. `pagesQueried` neçə olur? Niyə? (İpucu: `flatMap` daxili axınlara paralel, qabaqcadan abunə olur.)
2. Demo-da 100 000 sətirlik NDJSON başladıb 2 saniyə sonra "Dayandır" basın. Server statistikasında `pagesQueried` nə qədər artdı?

---

[← 10. Reaktiv streaming: `Flux` qaytarmaq kifayətdir](10-reaktiv-streaming.md) · [Mündəricat](README.md) · Növbəti: [12. gRPC nədir: müqavilə, protobuf, HTTP/2 →](12-grpc-nedir.md)
