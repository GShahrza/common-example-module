# 2. Server-Sent Events: chat cavabı söz-söz

[← 1. Servlet modeli: bir request, bir thread](01-servlet-modeli.md) · [Mündəricat](README.md) · Növbəti: [3. SSE ilə uzun işin gedişatı və yenidən qoşulma →](03-yeniden-qosulma.md)

**Hissə I: Spring MVC** · **Kod:** [`ChatController`](../mvc-streaming/src/main/java/io/github/gshahrza/streaming/mvc/chat/ChatController.java) · **Demo:** http://localhost:8080, 1-ci bölmə

---

## Həyatdan analogiya

Radio stansiyası yayım edir, siz isə radionu açıb dinləyirsiniz. Stansiyaya "növbəti mahnını göndər" deyə zəng etmirsiniz. Bir dəfə tezliyə qoşulursunuz, sonra yayım özü gəlir. Radio bir anlıq kəsilsə, cihaz yenidən tezliyi tapır.

**SSE (Server-Sent Events)** HTTP üzərində belə bir "radio"dur: brauzer bir dəfə qoşulur, server isə istədiyi vaxt hadisə göndərir.

## Problem

ChatGPT kimi bir cavab 5-10 saniyə çəkə bilər. Adi request-də istifadəçi bu müddət ərzində boş ekrana baxır. Hazır olan hər sözü dərhal göstərmək istəyirik.

## SSE formatı

SSE sadə mətn protokoludur, `Content-Type: text/event-stream`. Hər hadisə boş sətirlə bitən bir blokdur:

```
id:0
event:token
data:{"text":"You"}

id:1
event:token
data:{"text":" asked:"}

event:done
data:{"tokens":42}

```

| Sahə | Mənası |
|---|---|
| `data:` | Hadisənin məzmunu (məcburi) |
| `event:` | Hadisənin adı; brauzer `addEventListener('token', ...)` ilə dinləyir |
| `id:` | Hadisənin nömrəsi; yenidən qoşulanda istifadə olunur (növbəti fəsil) |
| `retry:` | Əlaqə kəsilsə, neçə ms sonra yenidən qoşulmaq |
| `:` ilə başlayan | Şərh; brauzer onu görməzdən gəlir |

## Həll addım-addım

**Addım 1.** Controller `SseEmitter` yaradıb dərhal qaytarır:

```java
@GetMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestParam String prompt) {
    SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
    ...
    return emitter;
}
```

Konstruktordakı rəqəm timeout-dur: 2 dəqiqədən sonra emitter özü bağlanır.

**Addım 2.** Client-in getdiyini bilmək üçün bayraq qururuq:

```java
AtomicBoolean open = new AtomicBoolean(true);
emitter.onCompletion(() -> open.set(false));
emitter.onTimeout(() -> open.set(false));
emitter.onError(e -> open.set(false));
```

İstifadəçi tab-ı bağlayanda Spring bu callback-ləri çağırır. Bayraq olmasa, server heç kimin oxumadığı cavabı istehsal etməyə davam edərdi.

**Addım 3.** Məlumatı başqa thread-də göndəririk:

```java
executor.execute(() -> {
    try {
        int index = 0;
        for (String token : generator.tokens(prompt)) {
            if (!open.get()) return;
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(index++))
                    .name("token")
                    .data(Map.of("text", token), MediaType.APPLICATION_JSON));
            Sleeper.sleep(properties.chatTokenDelay());
        }
        emitter.send(SseEmitter.event().name("done").data(...));
        emitter.complete();
    } catch (IOException e) {
        // brauzer getdi: "broken pipe"
    }
});
```

`emitter.send()` hadisəni yazır və dərhal flush edir. Hadisə şəbəkəyə gedir, cavab isə açıq qalır. `complete()` cavabı bağlayır.

**Addım 4.** Brauzer tərəfi ([`index.html`](../mvc-streaming/src/main/resources/static/index.html)):

```js
const es = new EventSource('/api/chat/stream?prompt=' + encodeURIComponent(prompt));
es.addEventListener('token', e => answer.textContent += JSON.parse(e.data).text);
es.addEventListener('done', () => es.close());
es.onerror = () => es.close();
```

`EventSource` brauzerə daxili qurulmuş SSE client-idir. Heç bir kitabxana lazım deyil.

## İki tələ

**1. Niyə mətn əvəzinə JSON?** SSE spesifikasiyası `data:`-dan sonrakı **bir boşluğu** silir. `" asked:"` tokeni xam mətn kimi göndərilsə, client `"asked:"` alar və sözlər bir-birinə yapışar: `Youasked:`. JSON-da boşluq dırnaq içindədir və qorunur.

**2. Niyə `done` və `es.close()`?** `EventSource` əlaqə bağlananda **avtomatik yenidən qoşulur**. Bu, radio üçün yaxşıdır, chat üçün isə pisdir: bağlamasanız, eyni sual yenidən göndərilər və cavab yenidən başlayar. Ona görə server "bitdi" deyir (`done`), client isə özü bağlayır.

## SSE-nin məhdudiyyətləri

- **Yalnız serverdən client-ə.** Client məlumat göndərmək üçün ayrıca request atır. İki tərəfli əlaqə üçün WebSocket və ya gRPC bidirectional (Hissə III) var.
- **Yalnız mətn.** Binary məlumat üçün base64 lazımdır.
- **`EventSource` yalnız GET göndərir və header təyin edə bilmir.** `Authorization: Bearer ...` göndərmək olmur. Ya cookie ilə auth, ya da `fetch` əsaslı SSE client istifadə olunur.
- **HTTP/1.1-də brauzer bir domenə 6 əlaqə açır.** 6 tab-da SSE açıqdırsa, 7-ci sorğu gözləyir. HTTP/2-də bu problem yoxdur.

## Yadda saxla

- SSE HTTP üzərində serverdən client-ə hadisə axınıdır, `text/event-stream`.
- `SseEmitter` qaytar, başqa thread-də `send()` et, sonda `complete()` çağır.
- Client-in getdiyini `onCompletion`/`onError` callback-ləri və `IOException` ilə tut.
- Brauzerdə `EventSource` işlət və bitəndə `close()` çağır.

## Tapşırıq

1. `.data(Map.of("text", token), ...)` əvəzinə `.data(token)` yazın. Chat-də sözlər necə görünür? Niyə?
2. Brauzerdə `es.addEventListener('done', ...)` sətrini silin. DevTools → Network-də `/api/chat/stream` neçə dəfə çağırılır?

---

[← 1. Servlet modeli: bir request, bir thread](01-servlet-modeli.md) · [Mündəricat](README.md) · Növbəti: [3. SSE ilə uzun işin gedişatı və yenidən qoşulma →](03-yeniden-qosulma.md)
