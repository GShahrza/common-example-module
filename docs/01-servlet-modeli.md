# 1. Servlet modeli: bir request, bir thread

[← Giriş: Cavab niyə hissə-hissə gəlməlidir?](00-giris.md) · [Mündəricat](README.md) · Növbəti: [2. Server-Sent Events: chat cavabı söz-söz →](02-sse.md)

**Hissə I: Spring MVC** · **Kod:** [`mvc-streaming`](../mvc-streaming)

---

## Həyatdan analogiya

Bankda hər müştəriyə bir operator təyin olunur. Operator müştərinin işini əvvəldən sona qədər aparır. Müştəri sənəd axtaranda operator da gözləyir və başqa heç kimə xidmət etmir. 200 operator varsa, eyni anda 200 müştəriyə xidmət olunur, 201-ci isə növbədə gözləyir.

Spring MVC-nin arxasındakı Tomcat məhz belə işləyir: **thread-per-request**.

## Adi controller necə işləyir

```java
@GetMapping("/api/orders")
public List<Order> all(@RequestParam int count) {
    return repository.stream(count).toList();   // 3 saniyə
}
```

1. Tomcat pool-dan bir thread götürür (default 200 thread var).
2. Thread controller-i çağırır və DB-ni gözləyərkən bloklanır.
3. Metod qayıdır, Spring `List`-i JSON-a çevirib göndərir.
4. Thread pool-a qayıdır.

Metod qayıtmayınca cavab başlaya bilməz. Streaming üçün isə metodun **dərhal qayıtması**, cavabın isə **açıq qalması** lazımdır.

## Async request: metod qayıdır, cavab açıq qalır

Servlet 3.0-dan bəri buna "async request" deyilir. Spring MVC-də bunun üç "qayıdan obyekti" var:

| Qayıdan tip | Nə üçün |
|---|---|
| `SseEmitter` | Server-Sent Events |
| `ResponseBodyEmitter` | İstənilən formatda obyektlər (NDJSON) |
| `StreamingResponseBody` | Xam baytlar (fayl) |

Üçünün də iş sxemi eynidir:

```
Tomcat thread-i:   controller() ──► emitter-i qaytar ──► azad oldu, pool-a qayıtdı
                                           │
Başqa thread:                              └──► send() ... send() ... send() ... complete()
                                                    │         │          │          │
Brauzer:                                          parça     parça      parça     bağlandı
```

Controller bir "boru" (emitter) yaradıb qaytarır, məlumatı isə başqa thread həmin borudan göndərir.

## "Başqa thread" haradan gəlir?

Bu layihədə bunun üçün virtual thread-lər istifadə olunur ([`ExecutorConfig`](../mvc-streaming/src/main/java/io/github/gshahrza/streaming/mvc/config/ExecutorConfig.java)):

```java
@Bean(destroyMethod = "close")
public ExecutorService streamingExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

və `application.yml`-də:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

**Virtual thread (Java 21)** JVM-in idarə etdiyi "yüngül" thread-dir. Adi (platform) thread stack üçün ~1 MB yaddaş rezerv edir və əməliyyat sistemi thread-idir, ona görə sayı minlərlə məhdudlaşır. Virtual thread-lər isə yüz minlərlədir və `sleep` və ya IO zamanı alt qatdakı platform thread-i azad edir.

Hər açıq stream bir producer thread tutur. 10 000 eyni vaxtlı stream platform thread-lərlə ağır olardı, virtual thread-lərlə isə problem deyil.

## Yadda saxla

- Tomcat hər request-ə bir thread verir. Metod qayıdana qədər cavab başlamır.
- Streaming üçün controller emitter qaytarır, məlumatı isə başqa thread yazır.
- Java 21 virtual thread-ləri çoxlu açıq stream-i ucuz edir.

## Tapşırıq

1. `/api/orders?count=5000` və `/api/orders/stream?count=5000` sorğularını DevTools-da açın. Hər ikisində "Waiting (TTFB)" vaxtını müqayisə edin.
2. `application.yml`-də `spring.threads.virtual.enabled: false` edin, `ExecutorConfig`-də isə `Executors.newFixedThreadPool(2)` yazın. İki tab-da eyni vaxtda chat açın, üçüncü tab-da nə baş verir?

---

[← Giriş: Cavab niyə hissə-hissə gəlməlidir?](00-giris.md) · [Mündəricat](README.md) · Növbəti: [2. Server-Sent Events: chat cavabı söz-söz →](02-sse.md)
