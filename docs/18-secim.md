# Sonsöz: Hansını nə vaxt seçməli?

[← 17. Brauzer, gateway və gRPC alətləri](17-brauzer-ve-gateway.md) · [Mündəricat](README.md)

---

Üç texnologiyanı gördünüz. Real layihədə sual belə qoyulur: "Mənim problemimə hansı uyğundur?" Bu fəsil bütün bələdçini bir yerə yığır.

## Ölçmə nəticələri: bir cədvəldə

Hamısı bu repoda, 4 nüvəli maşında, Docker-də ölçülüb:

| Nə ölçüldü | Nəticə | Fəsil |
|---|---|---|
| 2000 sətir: adi JSON, ilk sətir | 3036 ms | [4](04-ndjson.md) |
| 2000 sətir: NDJSON, ilk sətir | **155 ms** | [4](04-ndjson.md) |
| SSE chat, ilk söz | 7 ms | [2](02-sse.md) |
| WebFlux, 100 paralel sorğu: bloklayan kod | ~8000 ms | [7](07-event-loop.md) |
| WebFlux, 100 paralel sorğu: reaktiv kod | **~380 ms** | [7](07-event-loop.md) |
| 3 servis: ardıcıl / `Mono.zip` | 1200 / **500** ms | [9](09-paralel.md) |
| 100 000 sətirdən 150-si oxunub dayandırıldı: sorğulanan səhifə | 2 / 1000 | [11](11-backpressure.md) |
| 1000 sifariş: JSON / protobuf | 99.7 / **27.9** KB | [12](12-grpc-nedir.md) |

## Problemdən texnologiyaya

| Mənə lazımdır ki... | Seçim |
|---|---|
| ...brauzer chat cavabını söz-söz göstərsin | SSE ([2](02-sse.md), [10](10-reaktiv-streaming.md)) |
| ...uzun işin faizi canlı yenilənsin, əlaqə kəsilsə davam etsin | SSE + event id ([3](03-yeniden-qosulma.md)) |
| ...böyük siyahı yükləndikcə göstərilsin | NDJSON ([4](04-ndjson.md)) |
| ...milyon sətirlik fayl yaddaşı doldurmadan yüklənsin | `StreamingResponseBody` ([5](05-fayl-streaming.md)) |
| ...JPA/JDBC istifadə edən adi servis minlərlə əlaqəyə dözsün | Spring MVC + virtual thread-lər ([1](01-servlet-modeli.md)) |
| ...bir sorğu bir neçə servisi paralel çağırsın | WebFlux + `Mono.zip` ([9](09-paralel.md)); MVC-də virtual thread + `CompletableFuture` |
| ...yavaş client serveri boğmasın | WebFlux backpressure ([11](11-backpressure.md)), gRPC flow control ([14](14-server-streaming.md)) |
| ...servislər arası sürətli, tipli, çox dilli əlaqə | gRPC ([12](12-grpc-nedir.md)) |
| ...servis böyük məlumatı axınla göndərsin/qəbul etsin | gRPC server/client streaming ([14](14-server-streaming.md), [15](15-client-streaming.md)) |
| ...iki servis arasında davamlı iki tərəfli əlaqə | gRPC bidirectional ([16](16-bidirectional.md)) |
| ...brauzerlə iki tərəfli əlaqə | WebSocket (bu bələdçidə yoxdur) |

## Spring MVC, yoxsa WebFlux?

Java 21-dən sonra bu sualın cavabı dəyişib.

**Əvvəl:** "Çoxlu eyni vaxtlı əlaqə? Thread-lər bitir, WebFlux-a keç."

**İndi:** virtual thread-lər bloklayan kodu ucuz edir. Spring MVC + virtual thread-lər adi servislərdə WebFlux-un performans üstünlüyünün böyük hissəsini verir, üstəlik sadə kod, adi stack trace, JPA saxlanılır.

**WebFlux-u seçmək üçün səbəblər:**
- axınların birləşdirilməsi, çevrilməsi, filtrlənməsi mərkəzdədir (`Flux` operatorları çox güclüdür);
- backpressure lazımdır;
- bütün stack onsuz da reaktivdir (R2DBC, reaktiv Kafka və ya Mongo);
- API gateway və ya BFF: çoxlu servisi çağırıb birləşdirən, öz DB-si olmayan servis.

**WebFlux-u seçməmək üçün səbəblər:**
- JPA/JDBC-dən imtina etmək mümkün deyil (7-ci fəsil: bloklama WebFlux-u MVC-dən də yavaş edir);
- komanda reaktiv proqramlaşdırmaya yeni başlayır;
- adi CRUD.

## REST, yoxsa gRPC?

| | REST + SSE/NDJSON | gRPC |
|---|---|---|
| Kim çağırır | Brauzer, üçüncü tərəf | Öz servisləriniz, mobil |
| Müqavilə | İstəyə bağlı | Məcburi, tipli |
| Performans | Kifayətdir | Yüksək (binary, HTTP/2) |
| Streaming | Serverdən | 4 növ |
| Debug | Asan (curl, brauzer) | `grpcurl` lazımdır |

Çox yayılmış arxitektura hər ikisini birləşdirir: **xaricdə REST, daxildə gRPC**. Bu layihənin `grpc-streaming` modulu məhz bunu göstərir (17-ci fəsil).

## Bütün texnologiyalarda eyni qaydalar

Bələdçi boyu bu fikirlər dəfələrlə qarşınıza çıxdı:

1. **Client gedəndə istehsalı dayandır.** MVC-də bayraq və `IOException`, WebFlux-da `cancel`, gRPC-də `isCancelled()`.
2. **Yavaş client-i nəzərə al.** WebFlux-da backpressure, gRPC-də flow control.
3. **Mənbə tənbəl olsun.** Lazy `Stream`, `concatMap`, səhifə-səhifə oxumaq.
4. **Timeout və deadline qoy.** Heç bir əlaqə sonsuza qədər açıq qalmasın.
5. **Aradakı qatları yoxla.** Proxy buferləməsi və gzip streaming-i gizlicə öldürə bilər.
6. **Zamanlamanı test et.** Məzmun düzgün olsa belə, stream buferlənmiş ola bilər.

Uğurlar!

---

[← 17. Brauzer, gateway və gRPC alətləri](17-brauzer-ve-gateway.md) · [Mündəricat](README.md)
