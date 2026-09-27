# 5. Fayl yükləmə: `StreamingResponseBody`

[← 4. NDJSON: böyük siyahını sətir-sətir göndərmək](04-ndjson.md) · [Mündəricat](README.md) · Növbəti: [6. Production-da streaming: görünməyən tələlər →](06-production.md)

**Hissə I: Spring MVC** · **Kod:** [`OrderController.exportCsv`](../mvc-streaming/src/main/java/io/github/gshahrza/streaming/mvc/order/OrderController.java) · **Demo:** 4-cü bölmə

---

## Həyatdan analogiya

Hovuzu su ilə doldurmaq üçün iki yol var. Birincisi, əvvəlcə böyük bir çəni doldurub sonra hamısını birdən tökmək: çən lazımdır, gözləmək lazımdır. İkincisi, şlanqı birbaşa hovuza tutmaq: su dərhal axır, çənə ehtiyac yoxdur.

`StreamingResponseBody` şlanqdır: məlumat mənbədən birbaşa şəbəkəyə axır.

## Problem

"Bütün sifarişləri CSV kimi yüklə" düyməsi. 1 000 000 sətir ~80 MB-dır. Adi yanaşma belədir:

```java
String csv = orders.stream().map(this::toCsvLine).collect(joining("\n"));   // 80 MB String
return ResponseEntity.ok().body(csv.getBytes());                            // daha 80 MB byte[]
```

Nəticədə yaddaşda 160+ MB tutulur. 10 istifadəçi eyni anda basarsa, 1.6 GB olur. Üstəlik brauzer bütün fayl hazırlanana qədər yükləməyə başlamır.

## Həll

```java
@GetMapping("/api/orders/export.csv")
public ResponseEntity<StreamingResponseBody> exportCsv(@RequestParam(defaultValue = "100000") int count) {
    int total = validate(count, MAX_COUNT);
    StreamingResponseBody body = out -> {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.write("id,customer,amount,status,createdAt\n");
        for (List<Order> page : (Iterable<List<Order>>) repository.streamPages(total)::iterator) {
            for (Order o : page) {
                writer.write(o.id() + "," + csv(o.customer()) + "," + ... + "\n");
            }
            writer.flush();                 // bu səhifəni brauzerə göndər
        }
        writer.flush();
    };
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename("orders.csv").build().toString())
            .body(body);
}
```

Addım-addım:

1. **`StreamingResponseBody` bir lambda-dır**, `out -> { ... }`. Spring ona response-un `OutputStream`-ini verir.
2. **Lambda-nı Spring özü başqa thread-də işə salır.** `SseEmitter`-dən fərqli olaraq burada öz executor-umuza ehtiyac yoxdur. Spring MVC-nin async executor-u istifadə olunur, bu layihədə o da virtual thread-dir.
3. **Yaddaşda hər an yalnız bir səhifə var** (100 sətir) və `BufferedWriter`-in 8 KB buferi.
4. **`flush()` hər səhifədən sonra** məlumatı şəbəkəyə itələyir.
5. **`Content-Disposition: attachment`** brauzerə "bunu göstərmə, fayl kimi saxla" deyir.

## Brauzer tərəfi

```html
<a href="/api/orders/export.csv?count=100000">orders.csv yüklə</a>
```

Heç bir JavaScript lazım deyil. Brauzer yükləmə panelində faylın böyüdüyünü göstərir. Server `Content-Length` göndərmədiyi üçün irəliləyiş faizini bilmir, yalnız yüklənən həcmi göstərir.

## CSV escape

```java
private static String csv(String value) {
    return value.contains(",") || value.contains("\"")
            ? "\"" + value.replace("\"", "\"\"") + "\""
            : value;
}
```

Adda vergül olsa (`"Əliyev, Murad"`), dırnağa alınmalıdır. Dırnağın özü isə ikiqat yazılır. Bu, CSV standartının (RFC 4180) qaydasıdır.

## Üç emitter-in müqayisəsi

| | `SseEmitter` | `ResponseBodyEmitter` | `StreamingResponseBody` |
|---|---|---|---|
| Nə göndərir | SSE hadisələri | Obyektlər və ya mətn | Xam baytlar |
| Thread | Özün təmin edirsən | Özün təmin edirsən | Spring təmin edir |
| Client getdi | `onError` və `IOException` | `IOException` | `write()`-da `IOException` |
| Tipik istifadə | Chat, bildiriş | NDJSON | Fayl, zip, şəkil |

## Yadda saxla

- Böyük faylı yaddaşa yığma, `OutputStream`-ə birbaşa yaz.
- `StreamingResponseBody`-də thread-i Spring verir.
- Hər hissədən sonra `flush()`; `Content-Disposition: attachment` ilə fayl kimi yüklət.

## Tapşırıq

1. `count=1000000` ilə yükləyin və serverin yaddaşını izləyin (`docker stats`). Sonra kodu "bütün CSV-ni `StringBuilder`-də yığ" variantına dəyişib müqayisə edin.
2. Bir müştərinin adını `Aysel, "Baş"` edin və faylı Excel-də açın. Escape düzgün işləyirmi?

---

[← 4. NDJSON: böyük siyahını sətir-sətir göndərmək](04-ndjson.md) · [Mündəricat](README.md) · Növbəti: [6. Production-da streaming: görünməyən tələlər →](06-production.md)
