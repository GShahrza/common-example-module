# 15. Client streaming: çox sorğu, bir cavab

[← 14. Server streaming və flow control](14-server-streaming.md) · [Mündəricat](README.md) · Növbəti: [16. Bidirectional streaming: hər iki tərəf danışır →](16-bidirectional.md)

**Hissə III: gRPC** · **Kod:** [`OrderGrpcService.uploadOrders`](../grpc-streaming/src/main/java/io/github/gshahrza/streaming/grpc/server/OrderGrpcService.java), [`GatewayController.upload`](../grpc-streaming/src/main/java/io/github/gshahrza/streaming/grpc/gateway/GatewayController.java) · **Demo:** 3-cü bölmə

---

## Həyatdan analogiya

Mağazada kassaya mal qoyursunuz: bir-bir, bir-bir... Kassir hər malı oxuyur, amma çeki yalnız siz "bu qədər" deyəndə verir. Çoxlu giriş, bir nəticə.

```proto
rpc UploadOrders(stream Order) returns (UploadSummary);
```

İstifadə sahələri: toplu yükləmə, sensor məlumatları, log göndərmə, fayl parçaları.

## Server: rollar tərsinə dönür

Unary və server streaming-də server metodu sorğunu **parametr kimi** alır. Burada isə server bir **observer qaytarır**, gRPC isə client-in hər mesajını ona ötürür:

```java
@Override
public StreamObserver<Order> uploadOrders(StreamObserver<UploadSummary> responseObserver) {
    long start = System.nanoTime();
    return new StreamObserver<>() {
        private int received;
        private BigDecimal total = BigDecimal.ZERO;

        @Override
        public void onNext(Order order) {            // client-in hər mesajı
            received++;
            total = total.add(new BigDecimal(order.getAmount()));
        }

        @Override
        public void onError(Throwable t) {           // client ləğv etdi və ya xəta
            log.info("Upload aborted by client after {} orders", received);
        }

        @Override
        public void onCompleted() {                  // client: "bu qədər"
            responseObserver.onNext(UploadSummary.newBuilder()
                    .setReceived(received)
                    .setTotalAmount(total.toPlainString())
                    .setElapsedMs((System.nanoTime() - start) / 1_000_000)
                    .build());
            responseObserver.onCompleted();          // yeganə cavab
        }
    };
}
```

Server hər mesajı gəldikcə emal edir, bütün siyahını yaddaşda yığmır. 10 000 sifariş gəlsə də, yaddaşda yalnız sayğac və cəm saxlanılır.

## Client: async stub lazımdır

Blocking stub "bir sorğu göndər, cavabı gözlə" modelidir, streaming göndərmək üçün uyğun deyil. Burada **async stub** lazımdır:

```java
CompletableFuture<UploadSummary> result = new CompletableFuture<>();

StreamObserver<Order> requests = asyncStub.withDeadlineAfter(1, TimeUnit.MINUTES)
        .uploadOrders(new StreamObserver<>() {           // cavabı qəbul edən
            public void onNext(UploadSummary s) { result.complete(s); }
            public void onError(Throwable t) { result.completeExceptionally(t); }
            public void onCompleted() { }
        });

for (long id = 1; id <= count; id++) {
    requests.onNext(OrderData.order(id));               // göndər, göndər, göndər
}
requests.onCompleted();                                  // "bu qədər"

UploadSummary summary = result.get(1, TimeUnit.MINUTES);
```

İki observer var, adlarına diqqət edin:
- **`requests`**: biz ona yazırıq, mesajlar serverə gedir.
- **Parametr olaraq verdiyimiz observer**: gRPC ona yazır, serverin cavabı bizə gəlir.

Test bunu təsdiqləyir: `onCompleted()`-dən əvvəl cavab gəlmir.

```java
for (...) upload.onNext(...);
assertThat(result).isNotDone();      // server hələ cavab verməyib
upload.onCompleted();
UploadSummary summary = result.get(5, TimeUnit.SECONDS);
```

## REST ilə müqayisə

10 000 sifarişi yükləmək üçün REST-də iki yol var:
- 10 000 ayrı `POST`: çoxlu əlaqə və header, yavaşdır;
- bir böyük JSON massivi: hər iki tərəfdə yaddaşda yığılır.

gRPC-də isə bir çağırış, 10 000 kompakt mesaj var və server hər birini gəldikcə emal edir. Demo-da: `server qəbul etdi: 10000 sifariş · server vaxtı: ~600 ms`.

## Yadda saxla

- Client streaming-də server observer qaytarır, gRPC client-in mesajlarını ona ötürür.
- Cavab yalnız client `onCompleted()` deyəndə göndərilir.
- Client tərəfdə async stub lazımdır.
- Server mesajları gəldikcə emal edir və hamısını yaddaşa yığmır.

## Tapşırıq

1. `printf '{"id":1,"amount":"10.50"}\n{"id":2,"amount":"4.50"}\n' | grpcurl -plaintext -d @ localhost:9090 streaming.orders.v1.OrderService/UploadOrders` işə salın.
2. Server hər 1000 sifarişdən bir "irəliləyiş" cavabı göndərsin istəyirsiniz. Bu, hansı çağırış növüdür və `.proto`-da nə dəyişməlidir?

---

[← 14. Server streaming və flow control](14-server-streaming.md) · [Mündəricat](README.md) · Növbəti: [16. Bidirectional streaming: hər iki tərəf danışır →](16-bidirectional.md)
