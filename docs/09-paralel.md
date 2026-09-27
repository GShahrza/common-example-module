# 9. Paralel çağırışlar: `Mono.zip`

[← 8. `Mono` və `Flux`: gələcəyin təsviri](08-mono-flux.md) · [Mündəricat](README.md) · Növbəti: [10. Reaktiv streaming: `Flux` qaytarmaq kifayətdir →](10-reaktiv-streaming.md)

**Hissə II: WebFlux** · **Kod:** [`DashboardController`](../webflux-streaming/src/main/java/io/github/gshahrza/streaming/webflux/dashboard/DashboardController.java), [`RemoteServices`](../webflux-streaming/src/main/java/io/github/gshahrza/streaming/webflux/dashboard/RemoteServices.java) · **Demo:** 2-ci bölmə

---

## Həyatdan analogiya

Səhər yeməyi hazırlayırsınız: çay dəmləmək (5 dəq), yumurta bişirmək (7 dəq), çörək qızartmaq (3 dəq). Ardıcıl etsəniz 15 dəqiqə çəkir. Üçünü birdən ocağa qoysanız 7 dəqiqə, yəni ən uzunu qədər. Bunun üçün üç aşpaz lazım deyil: siz hər birini başladıb gözləyirsiniz.

## Problem

Dashboard səhifəsi üç servisdən məlumat istəyir:

| Servis | Cavab vaxtı |
|---|---|
| İstifadəçi | 300 ms |
| Sifarişlər | 500 ms |
| Tövsiyələr | 400 ms |

Üç çağırış bir-birindən asılı deyil. Ardıcıl çağırsaq, 1200 ms gözləyərik.

## Ardıcıl: `flatMap` zənciri

```java
@GetMapping("/api/dashboard/{userId}/sequential")
public Mono<DashboardResponse> sequential(@PathVariable String userId) {
    return services.user(userId)
            .flatMap(user -> services.orders(userId)
                    .flatMap(orders -> services.recommendations(userId)
                            .map(recs -> new Dashboard(user, orders, recs))))
            .elapsed()
            .map(t -> new DashboardResponse("sequential", t.getT1(), t.getT2()));
}
```

Hər `flatMap` əvvəlkinin nəticəsini gözləyir. Ölçmə: **~1200 ms**.

`flatMap` zəncirini asılılıq olanda istifadə edin: "əvvəl istifadəçini tap, **onun** şəhərinə görə tövsiyə al".

## Paralel: `Mono.zip`

```java
@GetMapping("/api/dashboard/{userId}/parallel")
public Mono<DashboardResponse> parallel(@PathVariable String userId) {
    return Mono.zip(services.user(userId), services.orders(userId), services.recommendations(userId))
            .map(t -> new Dashboard(t.getT1(), t.getT2(), t.getT3()))
            .elapsed()
            .map(t -> new DashboardResponse("parallel", t.getT1(), t.getT2()));
}
```

`zip` üç `Mono`-ya eyni anda abunə olur, hamısı cavab verəndə onları `Tuple3`-ə yığır. Ölçmə: **~500 ms**, yəni ən yavaşı qədər.

## Adi kodda eyni şey

```java
ExecutorService pool = Executors.newFixedThreadPool(3);
CompletableFuture<String> user = CompletableFuture.supplyAsync(() -> userClient.get(id), pool);
CompletableFuture<List<String>> orders = CompletableFuture.supplyAsync(() -> ordersClient.get(id), pool);
CompletableFuture<List<String>> recs = CompletableFuture.supplyAsync(() -> recsClient.get(id), pool);
CompletableFuture.allOf(user, orders, recs).join();
```

Bu da işləyir, amma hər sorğu 3 thread tutur və onlar 500 ms gözləyir. 1000 eyni vaxtlı istifadəçi olanda bu, 3000 bloklanmış thread deməkdir. `Mono.zip`-də isə **sıfır əlavə thread** var: üç HTTP sorğusu göndərilir və eyni event loop hər üçünün cavabını hadisə kimi alır.

Java 21-də virtual thread-lər və `StructuredTaskScope` bu fərqi xeyli azaldır. Bu barədə 18-ci fəsildə danışılır.

## Xəta olanda

```java
Mono.zip(a, b, c)   // biri xəta versə, zip dərhal xəta verir, qalanlar ləğv olunur
```

Bir servisin düşməsi bütün səhifəni sındırmamalıdırsa:

```java
Mono.zip(
    services.user(id),
    services.orders(id),
    services.recommendations(id).onErrorReturn(List.of())   // tövsiyə olmasa da olar
)
```

Timeout üçün `.timeout(Duration.ofMillis(800))` istifadə olunur.

## Yadda saxla

- Asılı addımlar üçün `flatMap` zənciri, müstəqil addımlar üçün `Mono.zip`.
- `zip` ən yavaş çağırış qədər vaxt aparır və əlavə thread yaratmır.
- Birinin xətası hamısını sındırır. Lazım olsa, `onErrorReturn` və `timeout` ilə idarə et.

## Tapşırıq

1. `RemoteServices.recommendations`-ı `Mono.error(new RuntimeException())` edin. `parallel` endpoint-i nə qaytarır? Sonra `onErrorReturn(List.of())` əlavə edin.
2. Tövsiyələr istifadəçinin adından asılı olsun: `user` gəldikdən sonra `recommendations(user)` çağırılsın, `orders` isə paralel qalsın. Hansı operatorları birləşdirməlisiniz?

---

[← 8. `Mono` və `Flux`: gələcəyin təsviri](08-mono-flux.md) · [Mündəricat](README.md) · Növbəti: [10. Reaktiv streaming: `Flux` qaytarmaq kifayətdir →](10-reaktiv-streaming.md)
