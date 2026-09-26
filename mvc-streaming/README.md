# Spring MVC ilə streaming

[← Əsas səhifə](../README.md)

Bu modul adi (servlet əsaslı) Spring MVC tətbiqində cavabı hissə-hissə göndərməyin üç əsas üsulunu göstərir. Demo səhifə: **http://localhost:8080**.

## Adi request və streaming request arasında fərq

**Adi request:** server bütün cavabı hazırlayır, yalnız sonra göndərir.

```
Brauzer ── GET /api/orders ──►  Server: 1-ci səhifə… 2-ci… … 20-ci  ──► [bütün JSON]
           gözləyir…              (3 saniyə)                          ilk bayt 3-cü saniyədə
```

**Streaming:** server hər hissəni hazır olan kimi göndərir, əlaqə sonda bağlanır.

```
Brauzer ── GET /api/orders/stream ──►  1-ci səhifə ──► göstər
                                        2-ci səhifə ──► göstər
                                        …               ilk sətir 0.15-ci saniyədə
```

Demo səhifənin 3-cü bölməsində eyni 5000 sətri hər iki üsulla yükləyib "ilk sətir" vaxtını müqayisə edə bilərsiniz.

Streaming üç halda faydalıdır:
- **İstifadəçi gözləmir:** chat cavabı söz-söz gəlir, uzun işin faizi canlı yenilənir.
- **Yaddaş qənaəti:** milyon sətirlik export serverin yaddaşına yığılmır.
- **Timeout riski azalır:** cavab dərhal başlayır, proxy/load balancer "cavab yoxdur" deyib əlaqəni kəsmir.

## Üç üsul

| | `SseEmitter` | `ResponseBodyEmitter` | `StreamingResponseBody` |
|---|---|---|---|
| Format | Server-Sent Events (`text/event-stream`) | İstənilən: NDJSON, mətn… | Xam baytlar (CSV, fayl, zip…) |
| Nə göndərilir | Hadisələr: `id`, `event`, `data` | Obyektlər (message converter ilə) | `OutputStream`-ə birbaşa yazılır |
| Brauzer tərəfi | `EventSource` (avtomatik yenidən qoşulma) | `fetch` + `ReadableStream` | Adi yükləmə linki |
| Tipik istifadə | Chat, bildirişlər, progress | Böyük JSON siyahılar | Export, böyük fayllar |
| Bu modulda | Nümunə 1 və 2 | Nümunə 3 | Nümunə 4 |

Üçünün də ümumi xüsusiyyəti var: controller metodu **dərhal qayıdır**, servlet thread-i azad olur, cavab isə açıq qalır. Məlumatı başqa bir thread yazır. Bu modulda həmin thread-lər virtual thread-lərdir (`ExecutorConfig`).

---

## Nümunə 1: Chat cavabı söz-söz (SSE)

`GET /api/chat/stream?prompt=...` · [`ChatController`](src/main/java/io/github/gshahrza/streaming/mvc/chat/ChatController.java)

```java
@GetMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestParam String prompt) {
    SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
    executor.execute(() -> {
        for (String token : generator.tokens(prompt)) {
            emitter.send(SseEmitter.event().name("token").data(Map.of("text", token), APPLICATION_JSON));
        }
        emitter.send(SseEmitter.event().name("done").data(...));
        emitter.complete();
    });
    return emitter;   // metod dərhal qayıdır, cavab açıq qalır
}
```

Şəbəkədə cavab belə görünür, hər blok gələn kimi brauzerə çatır:

```
id:0
event:token
data:{"text":"You"}

id:1
event:token
data:{"text":" asked:"}
```

Brauzer tərəfi:

```js
const es = new EventSource('/api/chat/stream?prompt=salam');
es.addEventListener('token', e => answer.textContent += JSON.parse(e.data).text);
es.addEventListener('done', () => es.close());
```

**Niyə mətn əvəzinə JSON?** SSE `data:`-dan sonrakı bir boşluğu silir. `" asked"` kimi token-lər xam mətn kimi göndərilsə, sözlər bir-birinə yapışar.

**Niyə `done` hadisəsi və `es.close()`?** `EventSource` əlaqə bağlananda avtomatik yenidən qoşulur. Bağlamasanız, eyni sual yenidən göndəriləcək.

## Nümunə 2: Uzun işin gedişatı və yenidən qoşulma

`POST /api/jobs`, sonra `GET /api/jobs/{id}/events` · [`JobController`](src/main/java/io/github/gshahrza/streaming/mvc/job/JobController.java)

1. `POST /api/jobs` işi başladır və dərhal `202 Accepted` qaytarır.
2. Brauzer `events` ünvanına SSE ilə qoşulur və hər addımda `progress` hadisəsi alır.
3. Hər hadisənin `id`-si var (`id:7`). Əlaqə kəsiləndə `EventSource` bir saniyə sonra (`retry` / `reconnectTime`) yenidən qoşulur və son gördüyü id-ni `Last-Event-ID` header-ində göndərir. Server də yalnız ondan sonrakı hadisələri göndərir.

```
id:17  event:progress  data:{"step":17,"percent":85,...}
   ✂ əlaqə kəsildi
GET /api/jobs/{id}/events   Last-Event-ID: 17
id:18  event:progress ...
```

Demo səhifədə "Əlaqəni 2 saniyəlik kəs" düyməsi bunu göstərir. Yeni `EventSource` header təyin edə bilmədiyi üçün əllə yenidən qoşulanda eyni dəyər `?lastEventId=` parametri ilə göndərilir.

**Heartbeat:** Uzun müddət hadisə olmasa, server 15 saniyədən bir `:heartbeat` şərhi göndərir. Brauzer onu görməzdən gəlir, amma proxy-lər və load balancer-lər "boş" əlaqəni bağlamır.

## Nümunə 3: Böyük siyahı, NDJSON

`GET /api/orders/stream?count=5000` · [`OrderController`](src/main/java/io/github/gshahrza/streaming/mvc/order/OrderController.java)

NDJSON (newline-delimited JSON) formatında hər sətir ayrıca, tam JSON obyektidir:

```
{"id":1,"customer":"Murad","amount":89.19,"status":"PAID","createdAt":"2026-01-01T00:00:37Z"}
{"id":2,"customer":"Leyla","amount":168.38,"status":"SHIPPED","createdAt":"2026-01-01T00:01:14Z"}
```

Adi JSON massivi (`[...]`) axırıncı `]` gəlməyincə parse oluna bilməz, NDJSON-u isə sətir-sətir parse etmək olar:

```java
ResponseBodyEmitter emitter = new ResponseBodyEmitter();
executor.execute(() -> {
    for (List<Order> page : repository.streamPages(total)) {   // DB-dən səhifə-səhifə
        emitter.send(page.stream().map(json::write).collect(joining("\n", "", "\n")));
    }
    emitter.complete();
});
return ResponseEntity.ok().contentType(APPLICATION_NDJSON).body(emitter);
```

Brauzer tərəfində `fetch` və `ReadableStream` istifadə olunur:

```js
const reader = (await fetch(url)).body.pipeThrough(new TextDecoderStream()).getReader();
let buffer = '';
while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  buffer += value;
  const lines = buffer.split('\n');
  buffer = lines.pop();                 // son parça yarımçıq sətir ola bilər
  lines.filter(Boolean).forEach(l => render(JSON.parse(l)));
}
```

Bir parça (chunk) bir sətrə bərabər deyil. Şəbəkə sətri ortasından bölə bilər, ona görə yarımçıq qalan hissəni növbəti parçaya qədər saxlamaq lazımdır.

**Hər sətirdə deyil, hər səhifədə bir `send()`:** Hər `send()` bir flush deməkdir. 100 sətri bir yazışda göndərmək şəbəkə baxımından daha səmərəlidir, streaming effekti isə qalır.

Real layihədə `OrderRepository.streamPages()` əvəzinə Spring Data JPA-da `Stream<Order>` qaytaran repository metodu (read-only transaction daxilində) və ya `JdbcTemplate.queryForStream()` istifadə olunur.

## Nümunə 4: Fayl yükləmə, CSV

`GET /api/orders/export.csv?count=100000` · [`OrderController`](src/main/java/io/github/gshahrza/streaming/mvc/order/OrderController.java)

```java
StreamingResponseBody body = out -> {
    Writer writer = new BufferedWriter(new OutputStreamWriter(out, UTF_8));
    writer.write("id,customer,amount,status,createdAt\n");
    for (List<Order> page : repository.streamPages(total)) {
        for (Order o : page) writer.write(...);
        writer.flush();                  // hər səhifədən sonra brauzerə göndər
    }
};
return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv"))
        .header(CONTENT_DISPOSITION, "attachment; filename=\"orders.csv\"")
        .body(body);
```

Yaddaşda istənilən anda yalnız bir səhifə (100 sətir) olur, fayl bir milyon sətir olsa belə. Brauzer yükləməni dərhal başladır.

---

## Vacib detallar

| Mövzu | Problem | Bu layihədə həll |
|---|---|---|
| **Client çıxıb gedir** | İstifadəçi tab-ı bağlayır, server boş yerə işləməyə davam edir | `onCompletion` / `onError` bayrağı və `send()`-in atdığı `IOException` ilə istehsal dayanır |
| **Thread-lər** | Hər açıq stream bir thread tutur | Virtual thread-lər: `spring.threads.virtual.enabled=true` və `ExecutorConfig` |
| **Timeout** | Açıq qalan stream-lər | `SseEmitter(timeout)` və `spring.mvc.async.request-timeout` |
| **Reverse proxy (nginx)** | nginx cavabı buferləyir və stream "bir parça" gəlir | `X-Accel-Buffering: no` header-i (`StreamingHeadersFilter`) |
| **Gzip** | Sıxma cavabı buferləyir | `server.compression.enabled=false` |
| **Keş** | Proxy/brauzer cavabı keşləyə bilər | `Cache-Control: no-cache` |
| **Autentifikasiya** | `EventSource` header (məs. `Authorization`) göndərə bilmir | Cookie ilə auth və ya `fetch` əsaslı SSE client |
| **Brauzer limiti** | HTTP/1.1-də bir domenə 6 əlaqə limiti var, açıq SSE-lər onu tuta bilər | Production-da HTTP/2 |

## curl ilə yoxlamaq

```bash
curl -N "http://localhost:8080/api/chat/stream?prompt=salam"
curl -N "http://localhost:8080/api/orders/stream?count=500"
curl -o orders.csv "http://localhost:8080/api/orders/export.csv?count=100000"
```

`-N` curl-ün öz buferləməsini söndürür. Nəticəni pipe ilə başqa əmrə ötürəndə buferləmə yenə də vaxt ölçmələrini təhrif edə bilər.

## Testlər

[`StreamingEndpointsTest`](src/test/java/io/github/gshahrza/streaming/mvc/StreamingEndpointsTest.java) real HTTP server açır və yalnız məzmunu deyil, **zamanlamanı** da yoxlayır: NDJSON və CSV-də ilk sətir cavab bitməzdən ən azı 500 ms əvvəl gəlməlidir, adi JSON isə yalnız sonda gəlməlidir.

```bash
./gradlew :mvc-streaming:test
```

## Növbəti addım: WebFlux

Bu modulda hər açıq stream bir (virtual) thread tutur və məlumatı həmin thread "itələyir". WebFlux-da isə stream `Flux` kimi təsvir olunur və thread tutmur. Bu fərq, eləcə də backpressure növbəti modulda göstəriləcək.
