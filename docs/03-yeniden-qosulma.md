# 3. SSE ilə uzun işin gedişatı və yenidən qoşulma

[← 2. Server-Sent Events: chat cavabı söz-söz](02-sse.md) · [Mündəricat](README.md) · Növbəti: [4. NDJSON: böyük siyahını sətir-sətir göndərmək →](04-ndjson.md)

**Hissə I: Spring MVC** · **Kod:** [`JobController`](../mvc-streaming/src/main/java/io/github/gshahrza/streaming/mvc/job/JobController.java), [`JobService`](../mvc-streaming/src/main/java/io/github/gshahrza/streaming/mvc/job/JobService.java) · **Demo:** 2-ci bölmə

---

## Həyatdan analogiya

Kitab oxuyursunuz və telefon zəng çalır. Kitabı bağlamazdan əvvəl səhifəyə əlfəcin qoyursunuz. Qayıdanda kitabı başdan yox, əlfəcindən açırsınız.

SSE-də əlfəcin **event id**-dir, `Last-Event-ID` header-i isə "əlfəcin haradadır" sualının cavabıdır.

## Problem

Hesabat hazırlanır: 20 addım, 8 saniyə. İstifadəçi faizi canlı görməlidir. Mobil internet bir anlıq kəsildi. İndi nə olmalıdır?

- Proses sıfırdan başlamamalıdır, çünki iş serverdə gedir.
- İstifadəçi itirdiyi addımları da görməlidir.

## Həll: iki ayrı şey

**1. İş və onun izlənməsi ayrılır.** İşi `POST` başladır, izləməni isə `GET` SSE ilə edirik:

```java
@PostMapping("/api/jobs")
public ResponseEntity<Map<String, String>> start() {
    Job job = jobService.start();                        // arxa planda başlayır
    String events = "/api/jobs/" + job.getId() + "/events";
    return ResponseEntity.accepted()                     // 202: qəbul olundu, iş gedir
            .location(URI.create(events))
            .body(Map.of("id", job.getId(), "events", events));
}
```

İş `JobService`-də öz thread-ində gedir və hər addımı `Job` obyektinə yazır. SSE əlaqəsi kəsilsə də iş davam edir.

**2. Hər hadisənin nömrəsi var.**

```java
emitter.send(SseEmitter.event()
        .id(String.valueOf(event.step()))       // ← əlfəcin
        .name("progress")
        .data(event, MediaType.APPLICATION_JSON));
```

**3. Brauzer yenidən qoşulanda son nömrəni özü göndərir.** Bu, `EventSource`-un daxili davranışıdır:

```
GET /api/jobs/abc/events          ──► id:1 ... id:17
   ✂ əlaqə kəsildi
   (1 saniyə gözlə: reconnectTime)
GET /api/jobs/abc/events
Last-Event-ID: 17                 ──► id:18, id:19, id:20, completed
```

Server header-i oxuyur və yalnız ondan sonrakıları göndərir:

```java
public SseEmitter events(@PathVariable String id,
                         @RequestHeader(name = "Last-Event-ID", required = false) Integer lastEventIdHeader,
                         @RequestParam(name = "lastEventId", required = false) Integer lastEventIdParam) {
    int lastEventId = lastEventIdHeader != null ? lastEventIdHeader
            : lastEventIdParam != null ? lastEventIdParam : 0;
    ...
    for (ProgressEvent event : job.eventsAfter(sent)) { ... }
```

**Niyə həm header, həm də query parametri?** `EventSource` header-i yalnız **avtomatik** yenidən qoşulmada göndərir. JavaScript-də əllə `new EventSource(url)` yaradanda header təyin etmək mümkün deyil. Demo-dakı "Əlaqəni kəs" düyməsi əllə qoşulur, ona görə `?lastEventId=17` göndərir.

## Heartbeat: "hələ buradayam"

Nginx, AWS ALB və digər proxy-lər 60 saniyə heç bir məlumat keçməyən əlaqəni "ölü" sayıb bağlayır. İş uzun bir addımda ilişibsə, SSE kəsilər. Həll SSE şərhidir:

```java
if (System.nanoTime() - lastWrite > HEARTBEAT_INTERVAL.toNanos()) {
    emitter.send(SseEmitter.event().comment("heartbeat"));   // ":heartbeat"
}
```

Brauzer şərhləri görməzdən gəlir, proxy isə əlaqədə trafik görür.

## `reconnectTime`

```java
emitter.send(SseEmitter.event().reconnectTime(1000).comment("connected"));
```

Bu, brauzerə `retry:1000` göndərir: "əlaqə kəsilsə, 1 saniyə sonra yenidən qoşul".

## Tələ: 404 stream açılmadan əvvəl

```java
Job job = jobService.find(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job " + id));
SseEmitter emitter = new SseEmitter(...);
```

Yoxlama emitter yaradılmazdan **əvvəl** edilir. Stream başlayandan sonra HTTP status artıq göndərilib (200), onu 404-ə dəyişmək mümkün deyil.

## Yadda saxla

- Uzun iş və onun SSE ilə izlənməsi ayrı şeylərdir: `POST` başladır, `GET` izləyir.
- Hadisəyə `id` ver. Server `Last-Event-ID`-dən sonrakıları göndərsin.
- Heartbeat şərhləri proxy-lərin əlaqəni bağlamasının qarşısını alır.
- Xəta statusunu stream başlamazdan əvvəl qaytar.

## Tapşırıq

1. Demo-da işi başladın və 10-cu addımda "Əlaqəni kəs" basın. Network-də ikinci sorğunun URL-inə və gələn ilk `id`-yə baxın.
2. `JobService`-də hər addımı 20 saniyə edin, `HEARTBEAT_INTERVAL`-ı 5 saniyə. `curl -N` ilə `:heartbeat` sətirlərini görün.

---

[← 2. Server-Sent Events: chat cavabı söz-söz](02-sse.md) · [Mündəricat](README.md) · Növbəti: [4. NDJSON: böyük siyahını sətir-sətir göndərmək →](04-ndjson.md)
