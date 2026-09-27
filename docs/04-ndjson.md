# 4. NDJSON: böyük siyahını sətir-sətir göndərmək

[← 3. SSE ilə uzun işin gedişatı və yenidən qoşulma](03-yeniden-qosulma.md) · [Mündəricat](README.md) · Növbəti: [5. Fayl yükləmə: `StreamingResponseBody` →](05-fayl-streaming.md)

**Hissə I: Spring MVC** · **Kod:** [`OrderController.stream`](../mvc-streaming/src/main/java/io/github/gshahrza/streaming/mvc/order/OrderController.java), [`OrderRepository`](../mvc-streaming/src/main/java/io/github/gshahrza/streaming/mvc/order/OrderRepository.java) · **Demo:** 3-cü bölmə

---

## Həyatdan analogiya

Poçtla 5000 səhifəlik kitab göndərirsiniz. Onu bir böyük qutuya qablaşdıra bilərsiniz. Onda alıcı qutunu yalnız hamısı çatanda açar. Ya da hər fəsli ayrıca zərfə qoya bilərsiniz. Onda alıcı birinci zərfi alan kimi oxumağa başlayır.

JSON massivi `[...]` böyük qutudur. **NDJSON** isə zərflərdir.

## Problem: JSON massivi yarımçıq parse olunmur

```json
[{"id":1,"customer":"Murad"},{"id":2,"customer":"Leyla"},{"id":3,"cust
```

Bu, yarımçıq massivdir. `JSON.parse()` xəta atacaq, çünki massiv `]` ilə bitməlidir. Baytlar tədricən gəlsə belə, brauzer onlardan sona qədər istifadə edə bilmir.

## NDJSON: Newline-Delimited JSON

Hər sətir ayrıca, tam JSON obyektidir:

```
{"id":1,"customer":"Murad","amount":89.19,"status":"PAID","createdAt":"2026-01-01T00:00:37Z"}
{"id":2,"customer":"Leyla","amount":168.38,"status":"SHIPPED","createdAt":"2026-01-01T00:01:14Z"}
```

`Content-Type: application/x-ndjson`. Hər `\n` gələndə bir obyekt hazırdır.

## Həll: server tərəfi

```java
@GetMapping("/api/orders/stream")
public ResponseEntity<ResponseBodyEmitter> stream(@RequestParam(defaultValue = "1000") int count) {
    int total = validate(count, MAX_COUNT);
    ResponseBodyEmitter emitter = new ResponseBodyEmitter();
    executor.execute(() -> {
        try {
            for (List<Order> page : (Iterable<List<Order>>) repository.streamPages(total)::iterator) {
                String lines = page.stream()
                        .map(jsonMapper::writeValueAsString)
                        .collect(Collectors.joining("\n", "", "\n"));
                emitter.send(lines);                     // bir səhifə = bir yazış
            }
            emitter.complete();
        } catch (IOException e) {
            // client getdi
        }
    });
    return ResponseEntity.ok().contentType(NDJSON).body(emitter);
}
```

Üç detala diqqət edin:

1. **`ResponseBodyEmitter`, `SseEmitter`-in "valideynidir".** O, SSE formatı olmadan, istənilən məzmunu göndərir.
2. **Content-Type `ResponseEntity`-də təyin olunur.** Emitter öz başına format bilmir.
3. **Hər sətir üçün yox, hər səhifə üçün bir `send()`.** Hər `send()` bir flush və bir şəbəkə paketi deməkdir. 100 sətri bir yazışda göndərmək daha səmərəlidir, streaming effekti isə qalır.

## Lazy repository

```java
public Stream<List<Order>> streamPages(int count) {
    return IntStream.range(0, pages).mapToObj(page -> {
        Sleeper.sleep(properties.orderPageDelay());   // "DB sorğusu"
        return ...;                                   // 100 sətir
    });
}
```

`Stream` tənbəldir (lazy): səhifə yalnız `for` dövrü ona çatanda "sorğulanır". Client 3-cü səhifədə getsə, dövr dayanır və qalan 17 səhifə heç vaxt oxunmur. Real layihədə bunun qarşılığı:
- Spring Data JPA-da `Stream<Order>` qaytaran repository metodu (read-only transaction daxilində);
- `JdbcTemplate.queryForStream()`.

## Client tərəfi: `fetch` + `ReadableStream`

`EventSource` yalnız SSE üçündür. NDJSON-u `fetch` ilə oxuyuruq:

```js
const res = await fetch('/api/orders/stream?count=5000', { signal: controller.signal });
const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
let buffer = '';
while (true) {
  const { value, done } = await reader.read();   // növbəti parça
  if (done) break;
  buffer += value;
  const lines = buffer.split('\n');
  buffer = lines.pop();                          // son parça yarımçıq ola bilər!
  for (const line of lines) {
    if (line) render(JSON.parse(line));
  }
}
```

**Ən vacib sətir `buffer = lines.pop()`-dur.** Şəbəkə sətirləri ortasından bölə bilər:

```
1-ci parça:  {"id":1,...}\n{"id":2,"cust
2-ci parça:  omer":"Leyla",...}\n
```

`split('\n')` birinci parçada `['{"id":1,...}', '{"id":2,"cust']` verir. Sonuncu hissə yarımçıqdır, onu saxlayıb növbəti parçaya birləşdiririk.

**Dayandırmaq:** `AbortController.abort()` `fetch`-i kəsir. Server `send()` zamanı `IOException` alır və istehsalı dayandırır.

## SSE, yoxsa NDJSON?

| | SSE | NDJSON |
|---|---|---|
| Brauzer API | `EventSource`, sadədir | `fetch` + reader, bir az kod |
| Yenidən qoşulma | Avtomatik | Özün yazırsan |
| HTTP metodu | Yalnız GET | İstənilən (POST body ilə) |
| Header (auth) | Göndərilə bilmir | Göndərilə bilər |
| Uyğun gəlir | Hadisələr, bildirişlər | Məlumat siyahıları, export |

## Yadda saxla

- JSON massivi yarımçıq parse olunmur, NDJSON-un isə hər sətri hazır obyektdir.
- `ResponseBodyEmitter` + `application/x-ndjson`; göndərişləri səhifə-səhifə qruplaşdır.
- Brauzerdə yarımçıq sətri buferdə saxla.
- Mənbə lazy olmalıdır ki, client gedəndə qalan məlumat oxunmasın.

## Tapşırıq

1. Demo-da 5000 sətri əvvəl NDJSON, sonra "Hamısını birdən" ilə yükləyin. İki "ilk sətir" vaxtını müqayisə edin.
2. `buffer = lines.pop()` sətrini `lines.pop()` ilə əvəz edin, yəni yarımçıq hissəni atın. Neçə sətir itir? Böyük `count` ilə sınayın.

---

[← 3. SSE ilə uzun işin gedişatı və yenidən qoşulma](03-yeniden-qosulma.md) · [Mündəricat](README.md) · Növbəti: [5. Fayl yükləmə: `StreamingResponseBody` →](05-fayl-streaming.md)
