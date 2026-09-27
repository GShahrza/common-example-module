# 14. Server streaming və flow control

[← 13. Unary: bir sorğu, bir cavab](13-unary.md) · [Mündəricat](README.md) · Növbəti: [15. Client streaming: çox sorğu, bir cavab →](15-client-streaming.md)

**Hissə III: gRPC** · **Kod:** [`OrderGrpcService.listOrders`](../grpc-streaming/src/main/java/io/github/gshahrza/streaming/grpc/server/OrderGrpcService.java), [`FlowControl`](../grpc-streaming/src/main/java/io/github/gshahrza/streaming/grpc/server/FlowControl.java) · **Demo:** 2-ci bölmə

---

## Həyatdan analogiya

Bir dəfə abunə olursunuz, qəzet hər səhər gəlir. Poçtalyon ağıllıdırsa, poçt qutunuz dolanda yeni qəzet atmır, boşalana qədər gözləyir. Ağılsız poçtalyon isə qəzetləri qapının qabağına yığır, yığın da böyüyür, böyüyür.

```proto
rpc ListOrders(ListOrdersRequest) returns (stream Order);
```

Bu, SSE və NDJSON-un gRPC qarşılığıdır. Fərqi tipli mesajlar və daxili flow control-dur.

## Server: sadə versiya və onun problemi

```java
public void listOrders(ListOrdersRequest request, StreamObserver<Order> observer) {
    for (Order order : allOrders) {
        observer.onNext(order);      // ← heç vaxt bloklanmır!
    }
    observer.onCompleted();
}
```

Bu işləyir, amma gizli bir problem var: **`onNext()` heç vaxt gözləmir.** Client yavaş oxuyursa (mobil şəbəkə, yavaş emal), mesajlar serverin yaddaşında növbəyə yığılır. Bir milyon sifariş göndərilirsə, bir milyon mesaj yaddaşda olur. Bu, ağılsız poçtalyondur.

## Flow control: `isReady()` gözləmək

HTTP/2-nin hər stream üçün "pəncərəsi" var: qəbul edən tərəf nə qədər məlumat qəbul etməyə hazırdır. gRPC bunu `isReady()` ilə göstərir:

```java
final class FlowControl {
    private final ServerCallStreamObserver<?> observer;
    private final Object lock = new Object();

    FlowControl(ServerCallStreamObserver<?> observer) {
        this.observer = observer;
        observer.setOnReadyHandler(() -> {          // pəncərə açılanda gRPC çağırır
            synchronized (lock) { lock.notifyAll(); }
        });
    }

    boolean awaitReady() throws InterruptedException {
        synchronized (lock) {
            while (!observer.isReady() && !observer.isCancelled()) {
                lock.wait(100);
            }
        }
        return !observer.isCancelled();
    }
}
```

İstehsalçı hər mesajdan əvvəl gözləyir:

```java
for (Order order : page) {
    if (!flow.awaitReady()) return;      // client hazır deyil → gözlə; ləğv olunubsa → çıx
    observer.onNext(order);
}
```

Bu, ağıllı poçtalyondur: server client-in sürəti ilə işləyir. 11-ci fəsildəki WebFlux backpressure ilə eyni ideyadır, sadəcə başqa qatdadır.

## Niyə ayrı thread?

```java
producers.execute(() -> {           // virtual thread
    for (...) { flow.awaitReady(); observer.onNext(...); }
});
```

gRPC bir çağırışın bütün callback-lərini (`listOrders`-in özü, `onReady`, `onCancel`) **ardıcıl, bir-bir** çatdırır. `listOrders` metodunun içində `awaitReady()` ilə gözləsək, `onReady` callback-i bizim metodun bitməsini gözləyəcək, biz isə `onReady`-ni gözləyəcəyik. Bu, deadlock-dur. Ona görə istehsal ayrı thread-də gedir, metod isə dərhal qayıdır.

## Ləğv

```java
observer.setOnCancelHandler(() -> log.info("Client cancelled ListOrders"));
```

Çağırış ləğv olunanda (client getdi, deadline bitdi) `isCancelled()` `true` olur, `awaitReady()` `false` qaytarır və dövr bitir.

## Client: stream `Iterator` kimi

```java
Iterator<Order> orders = blockingStub.withDeadlineAfter(5, TimeUnit.MINUTES)
        .listOrders(ListOrdersRequest.newBuilder().setCount(count).build());
while (orders.hasNext()) {
    Order next = orders.next();    // növbəti mesaj gələnə qədər gözləyir
}
```

Blocking stub-da client oxumursa, gRPC yeni mesaj qəbul etmir. HTTP/2 pəncərəsi dolur və serverin `isReady()`-si `false` olur. Backpressure beləcə bütün zəncir boyu işləyir.

## Brauzerdən gRPC server-ə qədər ləğv

Gateway gRPC çağırışını ləğv edilə bilən kontekstdə işlədir:

```java
Context.CancellableContext call = Context.current().withCancellation();
emitter.onCompletion(() -> call.cancel(null));     // brauzer getdi → gRPC çağırışını ləğv et
emitter.onError(e -> call.cancel(e));
executor.execute(() -> call.run(() -> { /* listOrders ... */ }));
```

Zəncir belədir: brauzer "Dayandır" basır → `fetch` kəsilir → gateway-in emitter-i bağlanır → `call.cancel()` → gRPC `RST_STREAM` göndərir → serverdə `onCancelHandler` işləyir → istehsal dayanır. Bu, real işə salmada yoxlanılıb: server `Client cancelled ListOrders` yazdı.

## Test: deadline

```java
@Test
void deadlineStopsASlowStream() {
    Iterator<Order> orders = blocking.withDeadlineAfter(200, TimeUnit.MILLISECONDS)
            .listOrders(ListOrdersRequest.newBuilder().setCount(10_000).build());
    assertThatThrownBy(() -> orders.forEachRemaining(o -> { }))
            .isInstanceOfSatisfying(StatusRuntimeException.class,
                    e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
}
```

## Yadda saxla

- Server streaming: bir sorğu, çoxlu `onNext`, sonda `onCompleted`.
- `onNext()` bloklanmır, ona görə flow control üçün `isReady()` və `setOnReadyHandler` işlət.
- İstehsalı ayrı thread-də apar, yoxsa callback-lərlə deadlock yaranır.
- Client tərəfində `Context.CancellableContext` ilə çağırışı ləğv etmək olar.

## Tapşırıq

1. `grpcurl -plaintext -d '{"count": 5}' localhost:9090 streaming.orders.v1.OrderService/ListOrders` işə salın.
2. Fikir tapşırığı: `FlowControl` olmasaydı və client hər mesajı 1 saniyəyə emal etsəydi, 1 000 000 sifarişlik stream-də serverin yaddaşına nə olardı?

---

[← 13. Unary: bir sorğu, bir cavab](13-unary.md) · [Mündəricat](README.md) · Növbəti: [15. Client streaming: çox sorğu, bir cavab →](15-client-streaming.md)
