# 16. Bidirectional streaming: hər iki tərəf danışır

[← 15. Client streaming: çox sorğu, bir cavab](15-client-streaming.md) · [Mündəricat](README.md) · Növbəti: [17. Brauzer, gateway və gRPC alətləri →](17-brauzer-ve-gateway.md)

**Hissə III: gRPC** · **Kod:** [`OrderGrpcService.chat`](../grpc-streaming/src/main/java/io/github/gshahrza/streaming/grpc/server/OrderGrpcService.java), [`GatewayController.chat`](../grpc-streaming/src/main/java/io/github/gshahrza/streaming/grpc/gateway/GatewayController.java) · **Demo:** 4-cü bölmə

---

## Həyatdan analogiya

Telefon danışığı rəsmi məktublaşmaya bənzəmir. Məktubda biri yazır, o biri cavab verir, növbə ilə. Telefonda isə hər kəs istədiyi vaxt danışır: siz sualı bitirməmiş həmsöhbət "hə, başa düşdüm" deyə bilər, siz də onun cavabının ortasında yeni sual verə bilərsiniz.

```proto
rpc Chat(stream ChatRequest) returns (stream ChatResponse);
```

Sorğu da, cavab da stream-dir. Hər iki axın **müstəqildir**: server cavab üçün client-in bitməsini gözləmir, client də sual üçün cavabın bitməsini gözləmir.

## Server

```java
@Override
public StreamObserver<ChatRequest> chat(StreamObserver<ChatResponse> responseObserver) {
    ServerCallStreamObserver<ChatResponse> observer = (ServerCallStreamObserver<ChatResponse>) responseObserver;
    ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    observer.setOnCancelHandler(writer::shutdownNow);

    return new StreamObserver<>() {
        @Override
        public void onNext(ChatRequest request) {
            writer.execute(() -> answer(request.getQuestion(), observer));   // cavabı növbəyə qoy
        }

        @Override
        public void onError(Throwable t) {
            writer.shutdownNow();
        }

        @Override
        public void onCompleted() {
            writer.execute(() -> {                     // növbədəki cavablardan sonra bitir
                if (!observer.isCancelled()) observer.onCompleted();
            });
            writer.shutdown();
        }
    };
}
```

Quruluş client streaming ilə eynidir (server observer qaytarır). Fərq odur ki, cavab **dərhal və çoxlu** gedə bilər.

## Ən vacib tələ: `StreamObserver` thread-safe deyil

Birinci sualın cavabı yazılarkən ikinci sual gəlir. Hər sual öz thread-ində cavablansaydı, iki thread eyni anda `observer.onNext()` çağırardı:

```
thread-1: onNext("Answer") ─┐
thread-2: onNext("Answer") ─┼─► mesajlar qarışır və ya IllegalStateException
thread-1: onNext(" to")   ──┘
```

gRPC sənədləri açıq yazır: `StreamObserver` metodları **eyni anda bir neçə thread-dən çağırılmamalıdır**. Həll **bir yazıcı thread**-dir: `newSingleThreadExecutor`. Hər sualın cavabı növbəyə düşür və cavablar sıra ilə, qarışmadan gedir.

## Client

```java
StreamObserver<ChatRequest> requests = asyncStub.chat(new StreamObserver<>() {
    public void onNext(ChatResponse response) {
        emitter.send(SseEmitter.event().name(response.getDone() ? "done" : "token").data(...));
    }
    public void onError(Throwable t) { emitter.completeWithError(t); }
    public void onCompleted() { emitter.send(...end...); emitter.complete(); }
});

for (String question : questions) {
    requests.onNext(ChatRequest.newBuilder().setQuestion(question).build());
    Thread.sleep(300);                  // cavablar bu arada artıq gəlir
}
requests.onCompleted();
```

Suallar 300 ms fasilə ilə göndərilir. Demo-da görürsünüz ki, ikinci sual göndərilən zaman birinci cavabın sözləri artıq gəlir. Bu, eyni gRPC çağırışında iki istiqamətli axındır.

Gateway cavabları brauzerə SSE kimi ötürür, hər hadisədə sual da var ki, brauzer cavabları qruplaşdıra bilsin.

## Ləğv iki tərəfdən

- **Brauzer getdi:** gateway `requests.onError(Status.CANCELLED...)` çağırır, çağırış ləğv olunur, serverdə `onCancelHandler` yazıcı thread-i dayandırır.
- **Server xətası:** client-in `onError`-u çağırılır, gateway SSE-ni xəta ilə bağlayır.

## Bidirectional, yoxsa WebSocket?

| | WebSocket | gRPC bidirectional |
|---|---|---|
| Brauzer | Birbaşa dəstəklənir | Birbaşa yox |
| Mesaj formatı | Özün təyin edirsən | `.proto` ilə tipli |
| Servislər arası | Nadir | Standart |
| Deadline, status kodları | Yoxdur | Var |

Brauzer ilə server arasında WebSocket, servislər arasında gRPC bidirectional seçilir.

## Yadda saxla

- Bidirectional-da iki müstəqil axın var. Heç bir tərəf digərinin bitməsini gözləmir.
- `StreamObserver` thread-safe deyil: bir çağırış üçün bir yazıcı thread.
- `onCompleted`-i növbədəki cavablardan sonra çağır.

## Tapşırıq

1. `printf '{"question":"salam"}\n{"question":"gRPC nədir?"}\n' | grpcurl -plaintext -d @ localhost:9090 streaming.orders.v1.OrderService/Chat` işə salın.
2. `newSingleThreadExecutor`-u `newVirtualThreadPerTaskExecutor` ilə əvəz edin və demo-da 5 sual göndərin. Cavablarda nə dəyişir? Serverin loglarına baxın.

---

[← 15. Client streaming: çox sorğu, bir cavab](15-client-streaming.md) · [Mündəricat](README.md) · Növbəti: [17. Brauzer, gateway və gRPC alətləri →](17-brauzer-ve-gateway.md)
