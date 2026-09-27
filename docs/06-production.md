# 6. Production-da streaming: görünməyən tələlər

[← 5. Fayl yükləmə: `StreamingResponseBody`](05-fayl-streaming.md) · [Mündəricat](README.md) · Növbəti: [7. Event loop: WebFlux adi request-dən nə ilə fərqlənir →](07-event-loop.md)

**Hissə I: Spring MVC** · **Kod:** [`StreamingHeadersFilter`](../mvc-streaming/src/main/java/io/github/gshahrza/streaming/mvc/config/StreamingHeadersFilter.java), [`application.yml`](../mvc-streaming/src/main/resources/application.yml)

---

## Həyatdan analogiya

Evinizdə su kranı mükəmməl işləyir, amma binanın girişindəki köhnə filtr suyu yığıb saatda bir dəfə buraxır. Kran günahkar deyil, amma nəticədə su "hissə-hissə" gəlmir.

Streaming kodunuz lokal olaraq mükəmməl işləyə bilər. Serverlə brauzer arasındakı hər qat (proxy, gzip, load balancer) isə cavabı öz buferində yığıb sonda bir dəfəyə buraxa bilər.

## Tələ 1: Reverse proxy buferləməsi

**Simptom:** lokal olaraq hər şey işləyir, production-da stream yalnız sonda bir parça kimi gəlir.

**Səbəb:** nginx default olaraq backend cavabını buferləyir (`proxy_buffering on`).

**Həll:** tətbiq nginx-ə "bu cavabı buferləmə" deyir:

```java
response.setHeader("X-Accel-Buffering", "no");
response.setHeader("Cache-Control", "no-cache");
```

Bu layihədə bunu [`StreamingHeadersFilter`](../mvc-streaming/src/main/java/io/github/gshahrza/streaming/mvc/config/StreamingHeadersFilter.java) edir. O, yalnız streaming endpoint-lərinə tətbiq olunur (`/stream`, `/events`, `.csv`). Alternativ olaraq nginx konfiqurasiyasında `proxy_buffering off;` yazmaq olar.

## Tələ 2: Gzip

**Səbəb:** sıxma alqoritmi effektiv işləmək üçün məlumatı bloklar halında yığır. Bəzi hallarda o, cavabı flush etmir.

**Həll:**

```yaml
server:
  compression:
    enabled: false
```

və ya sıxmanı streaming endpoint-lərindən çıxarmaq.

## Tələ 3: Client getdi, server işləyir

**Simptom:** istifadəçi tab-ı bağlayıb, amma serverdə CPU və DB yüklənməsi davam edir.

**Həll:** "client burdadırmı?" yoxlaması:

```java
emitter.onCompletion(() -> open.set(false));
emitter.onError(e -> open.set(false));
...
if (!open.get()) return;
```

və `send()`-in atdığı `IOException`-u tutub dövrü dayandırmaq. Spring MVC-də bu əl işidir. WebFlux-da isə ləğv avtomatikdir (Hissə II).

## Tələ 4: Timeout-lar

İki qat timeout var:

```java
new SseEmitter(Duration.ofMinutes(2).toMillis());   // bu emitter üçün
```

```yaml
spring:
  mvc:
    async:
      request-timeout: 5m      # bütün async cavablar üçün default
```

Timeout olmasa, "unudulmuş" əlaqələr sonsuza qədər açıq qalır. Load balancer-in idle timeout-u da nəzərə alınmalıdır, onun həlli heartbeat-dir (3-cü fəsil).

## Tələ 5: Thread-lər

Hər açıq stream bir producer thread tutur. Platform thread-lərlə 5000 eyni vaxtlı SSE 5000 əməliyyat sistemi thread-i və ~5 GB-a qədər rezerv olunmuş stack yaddaşı deməkdir. Həll Java 21 virtual thread-ləridir (1-ci fəsil):

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

## Tələ 6: `curl` "yalan deyir"

Bu layihədə bunu özüm yaşadım. `curl ... | awk` ilə ölçəndə NDJSON-un ilk sətri sonda gəlmiş kimi görünürdü. Server düzgün işləyirdi, amma pipe-a yazan `curl` öz çıxışını buferləyirdi. Düzgün ölçmə üsulları:
- `curl -N` və nəticəni birbaşa terminalda görmək;
- proqram daxilində ölçmək (bu layihənin testləri `HttpClient` ilə hər sətrin gəliş vaxtını qeyd edir).

## Streaming-i necə test etməli

Adi test yalnız məzmunu yoxlayır, bu isə kifayət deyil. Server bütün cavabı buferləyib sonda göndərsə belə, məzmun düzgün olacaq. Ona görə [`StreamingEndpointsTest`](../mvc-streaming/src/test/java/io/github/gshahrza/streaming/mvc/StreamingEndpointsTest.java) **vaxtı** da yoxlayır:

```java
TimedLines result = readLines(get("/api/orders/stream?count=1000"));   // 10 səhifə × 100 ms
assertThat(result.lines()).hasSize(1000);
assertThat(result.firstLineMillis()).isLessThan(result.totalMillis() - 500);
```

"İlk sətir sondan ən azı 500 ms əvvəl gəlməlidir." Kimsə səhvən buferləmə əlavə etsə, bu test qırmızı olacaq.

## Yoxlama siyahısı

- [ ] Proxy buferləməsi söndürülüb (`X-Accel-Buffering: no` və ya proxy konfiqurasiyası)
- [ ] Gzip streaming endpoint-lərində söndürülüb
- [ ] Client-in getdiyi aşkarlanır və istehsal dayanır
- [ ] Timeout-lar təyin olunub
- [ ] Uzun sükut üçün heartbeat var
- [ ] Virtual thread-lər və ya kifayət qədər thread var
- [ ] Testlər zamanlamanı yoxlayır
- [ ] Production-da HTTP/2 istifadə olunur (SSE üçün 6 əlaqə limiti)

## Tapşırıq

1. Kiçik nginx konfiqurasiyası ilə (`proxy_pass http://mvc-streaming:8080`) layihəni `compose.yaml`-a əlavə edin. `X-Accel-Buffering` header-ini filtrdən silin və fərqi görün.
2. `server.compression.enabled: true` və `min-response-size: 1` edin. NDJSON-un ilk sətri nə vaxt gəlir?

---

[← 5. Fayl yükləmə: `StreamingResponseBody`](05-fayl-streaming.md) · [Mündəricat](README.md) · Növbəti: [7. Event loop: WebFlux adi request-dən nə ilə fərqlənir →](07-event-loop.md)
