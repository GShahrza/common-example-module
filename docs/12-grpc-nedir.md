# 12. gRPC nədir: müqavilə, protobuf, HTTP/2

[← 11. Backpressure və ləğv](11-backpressure.md) · [Mündəricat](README.md) · Növbəti: [13. Unary: bir sorğu, bir cavab →](13-unary.md)

**Hissə III: gRPC** · **Kod:** [`orders.proto`](../grpc-streaming/src/main/proto/orders.proto), [`build.gradle`](../grpc-streaming/build.gradle)

---

## Həyatdan analogiya

İki ölkə arasında ticarət var. Hər dəfə sərhəddə tərəflər "bu qutuda nə var, necə yazılıb?" deyə mübahisə edirlər. Bu, REST/JSON dünyasıdır: hər tərəf öz başa düşdüyü kimi oxuyur. Sonra ölkələr müqavilə imzalayır: hər qutunun forması, etiketi, sahələrin sırası dəqiq yazılır. Artıq gömrük işçisi ilk baxışdan nə olduğunu bilir.

`.proto` faylı bu müqavilədir. Hər iki tərəfin kodu ondan avtomatik yaranır, ona görə "razılaşmamazlıq" mümkün olmur.

## Üç tərkib hissəsi

### 1. Müqavilə: `.proto`

```proto
syntax = "proto3";
package streaming.orders.v1;

service OrderService {
  rpc GetOrder(GetOrderRequest) returns (Order);
  rpc ListOrders(ListOrdersRequest) returns (stream Order);
  rpc UploadOrders(stream Order) returns (UploadSummary);
  rpc Chat(stream ChatRequest) returns (stream ChatResponse);
}

message Order {
  int64 id = 1;
  string customer = 2;
  string amount = 3;
  Status status = 4;
  google.protobuf.Timestamp created_at = 5;
}
```

`service` metodları, `message` isə məlumat strukturlarını təsvir edir. `stream` sözü streaming-i bildirir. Onun yeri (sorğuda, cavabda, hər ikisində) dörd çağırış növünü müəyyən edir (13-16-cı fəsillər).

### 2. Protobuf: binary format

`= 1`, `= 2` sahənin **nömrəsidir**. Binary formatda sahənin adı yox, yalnız bu nömrə göndərilir:

```
JSON:      {"id":7,"customer":"Elvin","amount":"564.33",...}   ← hər dəfə "customer" sözü
Protobuf:  08 07 12 05 45 6C 76 69 6E 1A 06 ...                ← 0x12 = "2-ci sahə, mətn"
```

Bu layihədə 1000 sifariş ölçülüb (`/api/size`): **protobuf 27.9 KB, JSON 99.7 KB, fərq 3.6 dəfə**. Parse etmək də sürətlidir, çünki mətn təhlili yoxdur.

Nömrə müqavilənin parçasıdır. Sahənin **adını** dəyişmək təhlükəsizdir, **nömrəsini** dəyişmək isə köhnə client-ləri sındırır. Yeni sahəni yeni nömrə ilə əlavə etmək geriyə uyğundur: köhnə client onu sadəcə görməzdən gəlir.

**Niyə `amount` `string`-dir?** Protobuf-da `decimal` tipi yoxdur. `double` isə `0.1 + 0.2 = 0.30000000000000004` problemini yaradır, pul üçün qəbuledilməzdir. Seçim ya `string`, ya da qəpik ilə `int64`-dür.

### 3. HTTP/2

gRPC yalnız HTTP/2 üzərində işləyir:
- **Multiplexing:** bir TCP əlaqəsində yüzlərlə paralel çağırış olur, HTTP/1.1-dəki kimi "bir əlaqə, bir sorğu" məhdudiyyəti yoxdur.
- **Stream-lər:** hər çağırış bir HTTP/2 stream-dir və hər iki tərəf ona istədiyi vaxt yaza bilər. Bidirectional streaming bunun sayəsində mümkündür.
- **Flow control:** hər stream-in "pəncərəsi" var. Qəbul edən hazır deyilsə, göndərən gözləyir (14-cü fəsil).

## Kod generasiyası

```gradle
plugins {
    id 'com.google.protobuf' version '0.10.0'
}
protobuf {
    protoc { artifact = 'com.google.protobuf:protoc:4.35.1' }
    plugins { grpc { artifact = 'io.grpc:protoc-gen-grpc-java:1.83.1' } }
    generateProtoTasks { all()*.plugins { grpc { } } }
}
```

`./gradlew build` zamanı `protoc` kompilyatoru yüklənir və `build/generated/` qovluğunda bu siniflər yaranır:

| Sinif | Nədir |
|---|---|
| `Order`, `GetOrderRequest`, ... | Mesajlar: immutable, builder ilə yaradılır |
| `OrderServiceGrpc.OrderServiceImplBase` | Server bunu extend edir |
| `OrderServiceGrpc.OrderServiceBlockingStub` | Sinxron client |
| `OrderServiceGrpc.OrderServiceStub` | Asinxron client (streaming üçün) |

```java
Order order = Order.newBuilder().setId(7).setCustomer("Elvin").build();
```

## Spring Boot 4.1 ilə

```gradle
implementation 'org.springframework.boot:spring-boot-starter-grpc-server'
implementation 'org.springframework.boot:spring-boot-starter-grpc-client'
```

- `ImplBase`-i extend edən `@Service` avtomatik tapılır və 9090 portunda qeydiyyatdan keçir.
- **Reflection** (servisləri `.proto`-suz kəşf etmək) və **health** (sağlamlıq yoxlaması) servisləri avtomatik əlavə olunur.
- Client kanalları `application.yml`-də adla təyin olunur:

```yaml
spring:
  grpc:
    server:
      port: 9090
    client:
      channel:
        orders:
          target: static://localhost:9090
```

## REST və gRPC

| | REST/JSON | gRPC |
|---|---|---|
| Müqavilə | İstəyə bağlı (OpenAPI) | Məcburi, kod ondan yaranır |
| Ölçü | Böyük, mətn | 3-10 dəfə kiçik, binary |
| Streaming | Yalnız serverdən (SSE, NDJSON) | 4 növ, hər iki istiqamətdə |
| Brauzer | Birbaşa | Gateway və ya gRPC-Web lazımdır |
| İnsan oxuya bilər | curl, brauzer | `grpcurl` lazımdır |
| Harada güclüdür | Public API, frontend | Mikroservislər arası |

## Yadda saxla

- `.proto` müqavilədir, server və client kodu ondan yaranır.
- Protobuf sahə nömrələri ilə binary formatdır. Nömrələri dəyişmə.
- HTTP/2 bir əlaqədə çoxlu çağırış, iki istiqamətli stream və flow control verir.
- Spring Boot 4.1-də gRPC starter-ləri rəsmidir.

## Tapşırıq

1. `grpcurl -plaintext localhost:9090 describe streaming.orders.v1.Order` işə salın. Reflection nə göstərir?
2. Fikir tapşırığı: serverdə `Order`-ə `string note = 6;` əlavə olunub, başqa komandanın client-i isə köhnə `.proto` ilə build olunub. Client 6-cı sahəni alanda nə baş verir? `customer = 2`-nin nömrəsini `7` etsəydik nə olardı?

---

[← 11. Backpressure və ləğv](11-backpressure.md) · [Mündəricat](README.md) · Növbəti: [13. Unary: bir sorğu, bir cavab →](13-unary.md)
