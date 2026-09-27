# 13. Unary: bir sorğu, bir cavab

[← 12. gRPC nədir: müqavilə, protobuf, HTTP/2](12-grpc-nedir.md) · [Mündəricat](README.md) · Növbəti: [14. Server streaming və flow control →](14-server-streaming.md)

**Hissə III: gRPC** · **Kod:** [`OrderGrpcService.getOrder`](../grpc-streaming/src/main/java/io/github/gshahrza/streaming/grpc/server/OrderGrpcService.java), [`GatewayController.get`](../grpc-streaming/src/main/java/io/github/gshahrza/streaming/grpc/gateway/GatewayController.java) · **Demo:** http://localhost:8082, 1-ci bölmə

---

## Həyatdan analogiya

Telefonla məlumat xidmətinə zəng edirsiniz: "7 nömrəli sifarişin statusu nədir?" Operator cavab verir və zəng bitir. Hər sualı bir zəngə, hər zəngi bir cavaba bağlayan bu sadə formatdır, adi REST sorğusuna bənzəyir.

```proto
rpc GetOrder(GetOrderRequest) returns (Order);
```

## Server

```java
@Service
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<Order> responseObserver) {
        if (request.getId() < 1) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("id must be positive").asRuntimeException());
            return;
        }
        if (request.getId() > OrderData.MAX_ID) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Order " + request.getId() + " not found").asRuntimeException());
            return;
        }
        responseObserver.onNext(OrderData.order(request.getId()));
        responseObserver.onCompleted();
    }
}
```

Diqqət edin: metod `Order` **qaytarmır**, `void`-dir. Cavab `StreamObserver`-ə yazılır. Bu, dörd çağırış növünün hamısı üçün eyni modeldir, unary sadəcə "bir `onNext`, sonra `onCompleted`" halıdır.

| Metod | Mənası |
|---|---|
| `onNext(msg)` | Bir mesaj göndər |
| `onCompleted()` | Uğurla bitdi |
| `onError(status)` | Xəta ilə bitdi |

## Status kodları

gRPC HTTP status kodlarını deyil, öz kodlarını istifadə edir:

| gRPC | Mənası | HTTP qarşılığı |
|---|---|---|
| `OK` | Uğurlu | 200 |
| `INVALID_ARGUMENT` | Sorğu səhvdir | 400 |
| `NOT_FOUND` | Tapılmadı | 404 |
| `ALREADY_EXISTS` | Artıq var | 409 |
| `PERMISSION_DENIED` | İcazə yoxdur | 403 |
| `UNAUTHENTICATED` | Autentifikasiya yoxdur | 401 |
| `DEADLINE_EXCEEDED` | Vaxt bitdi | 504 |
| `UNAVAILABLE` | Servis əlçatmazdır (retry etmək olar) | 503 |
| `CANCELLED` | Client ləğv etdi | 499 |
| `INTERNAL` | Server xətası | 500 |

## Client

```java
Order order = blockingStub.withDeadlineAfter(2, TimeUnit.SECONDS)
        .getOrder(GetOrderRequest.newBuilder().setId(id).build());
```

**Blocking stub** adi metod çağırışına bənzəyir: cavabı qaytarır və ya `StatusRuntimeException` atır.

## Deadline: gRPC-nin gizli super gücü

REST-də timeout yalnız client-in öz işidir. Client 2 saniyədən sonra gözləməyi dayandırır, server isə bundan xəbərsiz işləməyə davam edir.

gRPC-də **deadline sorğu ilə birlikdə serverə gedir** (`grpc-timeout` header-i). Server onu görür, müddət bitəndə çağırış hər iki tərəfdə `DEADLINE_EXCEEDED` ilə bitir. Server A servis B-ni çağırırsa, deadline zəncir boyu ötürülə bilər: "istifadəçinin cəmi 2 saniyəsi qalıb".

Qızıl qayda: **hər gRPC çağırışına deadline verin.** Deadline-sız çağırış server ilişəndə sonsuza qədər gözləyə bilər.

## Gateway-də HTTP-yə çevirmək

Brauzer gRPC statusunu başa düşmür. Gateway onu HTTP-yə çevirir:

```java
@ExceptionHandler(StatusRuntimeException.class)
public ResponseEntity<Map<String, String>> grpcError(StatusRuntimeException e) {
    HttpStatus http = switch (e.getStatus().getCode()) {
        case NOT_FOUND -> HttpStatus.NOT_FOUND;
        case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
        case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
        case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        default -> HttpStatus.BAD_GATEWAY;
    };
    ...
}
```

```bash
$ curl localhost:8082/api/orders/9999999
{"grpcStatus":"NOT_FOUND","message":"Order 9999999 not found"}     # HTTP 404
```

## Yadda saxla

- gRPC metodu `void`-dir, cavab `StreamObserver`-ə yazılır: `onNext`, `onCompleted`, `onError`.
- Xətalar gRPC status kodları ilə ötürülür, HTTP kodları ilə yox.
- Deadline serverə də ötürülür. Hər çağırışa deadline ver.

## Tapşırıq

1. `grpcurl -plaintext -d '{"id": 0}' localhost:9090 streaming.orders.v1.OrderService/GetOrder` işə salın. Hansı kod gəlir?
2. `getOrder`-ə `Thread.sleep(3000)` əlavə edin və demo-da sifariş istəyin. Gateway hansı HTTP statusu qaytarır? Niyə?

---

[← 12. gRPC nədir: müqavilə, protobuf, HTTP/2](12-grpc-nedir.md) · [Mündəricat](README.md) · Növbəti: [14. Server streaming və flow control →](14-server-streaming.md)
