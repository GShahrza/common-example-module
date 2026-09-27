# 25. Spring AI: generativ AI-ı backend-ə qoşmaq

[← 24. Observability](24-observability.md) · [Mündəricat](README.md)

**Hissə IV: Əlavə mövzular** · **Kod:** [`ChatController`](../spring-ai/src/main/java/io/github/gshahrza/ai/chat/ChatController.java), [`ExtractController`](../spring-ai/src/main/java/io/github/gshahrza/ai/extract/ExtractController.java), [`OrderTools`](../spring-ai/src/main/java/io/github/gshahrza/ai/tools/OrderTools.java), [`DocsIngestion`](../spring-ai/src/main/java/io/github/gshahrza/ai/rag/DocsIngestion.java), [`RagController`](../spring-ai/src/main/java/io/github/gshahrza/ai/rag/RagController.java), [`FakeModels`](../spring-ai/src/test/java/io/github/gshahrza/ai/FakeModels.java) · **Demo:** http://localhost:8083

```bash
docker run -d --name ollama -p 11434:11434 -v ollama:/root/.ollama ollama/ollama
docker exec ollama ollama pull qwen2.5:3b
docker exec ollama ollama pull nomic-embed-text
./gradlew :spring-ai:bootRun
```

> **Dürüst qeyd:** bu modulun kodu və testləri işləyir, amma real LLM ilə yoxlanılmayıb. Onu yazdığım mühit Ollama modellərini yükləməyə icazə vermədi. Testlər saxta modellərlə işləyir (7-ci addım). Fəsildə real modelin davranışı haqqında deyilənlər ümumi təcrübədir, bu modulda ölçülməyib.

---

## Həyatdan analogiya

Şirkətə çox savadlı bir **təcrübəçi** gəlib. O, dünyada nə varsa, hamısını oxuyub, amma:

- **Sizin şirkəti tanımır:** daxili qaydaları, sifarişləri, sənədləri bilmir.
- **Yaddaşı yoxdur:** hər dəfə danışanda sizi ilk dəfə görür. Söhbəti davam etdirmək üçün ona əvvəlki yazışmanı hər dəfə yenidən göstərməlisiniz.
- **Bilmədiyini bəzən uydurur**, həm də çox inamla.

Onu faydalı etmək üçün:

- Sual gələndə **şirkət kitabçasından lazımi səhifələri** masasına qoyursunuz və deyirsiniz: "yalnız bunlara əsasən cavab ver". Bu, **RAG**-dır.
- Cavabı sərbəst mətn kimi yox, **blank** doldurmaq kimi istəyirsiniz. Bu, **structured output**-dur.
- Ona **daxili telefon nömrələri** verirsiniz: "sifarişin statusunu anbardan soruş". O, zəng etmir, "bu nömrəyə zəng et" deyir, zəngi siz edirsiniz. Bu, **tool calling**-dir.
- Söhbət qeydlərini hər dəfə ona qaytarırsınız. Bu, **chat memory**-dir.

**Spring AI** bu təcrübəçi ilə işləməyin qaydalarıdır. Təcrübəçinin özü (OpenAI, Anthropic, Ollama...) isə dəyişdirilə bilər.

## Problem

Biznes deyir: "Saytımıza chat əlavə edək: müştəri sifarişini soruşsun, ləğv etsin, sənədlərimizdən cavab alsın." LLM API-sini birbaşa çağırmaq asan görünür, amma real tətbiqdə suallar çoxalır:

- Cavab 10-20 saniyə çəkir: istifadəçi boş ekrana baxır.
- Model sifarişlər haqqında heç nə bilmir, ona görə uydurur.
- Cavabı kodda istifadə etmək lazımdır (JSON), model isə sərbəst mətn yazır.
- Model provayderi dəyişəndə (qiymət, keyfiyyət, məxfilik) bütün kod yenidən yazılır.
- Test etmək lazımdır, amma hər test üçün pullu və qeyri-deterministik API çağırmaq olmaz.

## LLM-in əsasları: bilmək lazım olan minimum

| Anlayış | Nədir | Nə üçün vacibdir |
|---|---|---|
| **Token** | Mətn parçası (söz və ya söz hissəsi) | Qiymət, limit və sürət token-lə ölçülür. Azərbaycan dilində bir söz çox vaxt bir neçə token olur |
| **Kontekst pəncərəsi** | Modelin bir sorğuda görə bildiyi maksimum token (prompt + cavab) | Uzun söhbət və böyük sənəd buna sığmalıdır |
| **Prompt** | Mesajlar: `system` (qaydalar, rol), `user` (sual), `assistant` (əvvəlki cavablar) | Modelin davranışını system prompt müəyyən edir |
| **Temperature** | Təsadüfilik (0 ≈ deterministik, 1+ = yaradıcı) | Faktlar və JSON üçün aşağı; bu modulda 0.3 |
| **Stateless** | Model əvvəlki sorğuları xatırlamır | Yaddaş tətbiqin işidir |
| **Hallucination** | İnamla uydurulmuş cavab | RAG, tool calling və "bilmirəm de" qaydası azaldır, tam aradan qaldırmır |
| **Embedding** | Mətnin məna vektoru | Mənaca yaxın mətnləri tapmaq (RAG-ın əsası) |

## Spring AI-ın quruluşu

```
ChatClient (fluent API)                     ← kodunuz bununla danışır
   │  advisors: yaddaş, RAG, loglama, guardrail
   ▼
ChatModel (interfeys) ─► OllamaChatModel | OpenAiChatModel | AnthropicChatModel | ...
EmbeddingModel        ─► OllamaEmbeddingModel | ...
VectorStore           ─► SimpleVectorStore | PGvector | Redis | Elasticsearch | Qdrant | ...
DocumentReader, TextSplitter, @Tool, ChatMemory
```

Kod `ChatClient`, `EmbeddingModel` və `VectorStore` **interfeysləri** ilə yazılır. Provayderi dəyişmək üçün starter-i dəyişmək (`spring-ai-starter-model-ollama` → `...-openai` / `...-anthropic`) və konfiqurasiyanı yeniləmək kifayətdir.

## Həll, addım-addım

### 1. Streaming chat və yaddaş

```java
public ChatController(ChatClient.Builder builder, ChatMemory chatMemory) {
    this.chatClient = builder
            .defaultSystem("""
                    You are a friendly assistant in a demo application about Spring and streaming.
                    Answer in the language of the user's question. Keep answers short.
                    """)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
}

@GetMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<Map<String, String>>> stream(@RequestParam String message, @RequestParam String conversationId) {
    return chatClient.prompt()
            .user(message)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .stream()
            .content()                                               // Flux<String>: token-lər gəldikcə
            .map(token -> event("token", Map.of("text", token)))
            .concatWith(Flux.just(event("done", Map.of())))
            .onErrorResume(e -> Flux.just(event("error", Map.of("message", e.getMessage()))));
}
```

- **Streaming:** LLM cavabı token-token yaradır. `.stream()` onları gəldikcə `Flux` kimi verir, SSE isə brauzerə çatdırır. Bu, [2-ci fəsildəki](02-sse.md) SSE-nin ən məşhur istifadəsidir: ChatGPT də belə işləyir.
- **Xəta event kimi göndərilir.** SSE cavabı artıq `200` ilə başlayıb, ona görə statusu dəyişmək olmur (3-cü fəsildəki tələ).
- **Yaddaş:** model stateless-dir. `MessageChatMemoryAdvisor` `conversationId` üzrə son mesajları (default 20) saxlayır və hər yeni prompt-a əlavə edir. Test bunu yoxlayır: ikinci sualda modelə gedən prompt birinci mesajı da ehtiva edir.
- **Advisor** Servlet filtrinə bənzəyir: prompt modelə getməzdən əvvəl və cavab qayıdandan sonra işləyir. Yaddaş, RAG, loglama və guardrail-lər advisor kimi qurulur.

Yaddaşın qiyməti də var: hər mesajla prompt böyüyür, ona görə token xərci və gecikmə artır. Uzun söhbətlərdə köhnə mesajlar ya kəsilir (window), ya da xülasə edilir.

### 2. Structured output: mətndən Java obyekti

```java
public record OrderDraft(String customer, List<Item> items, BigDecimal total, String currency, String deliveryCity) {
    public record Item(String product, int quantity, BigDecimal unitPrice) { }
}

OrderDraft draft = chatClient.prompt().user(text).call().entity(OrderDraft.class);
```

Spring AI record-dan **JSON Schema** yaradır, prompt-a "cavabı bu sxemdə JSON kimi ver" təlimatını əlavə edir, sonra cavabı Jackson ilə parse edir. Giriş sərbəst mətndir ("Salam, mən Rəşad. 2 ədəd Keyboard... Gəncəyə çatdırın."), çıxış isə tipli obyektdir.

Real istifadələr: e-poçtdan sifariş, CV-dən məlumat, müqavilədən tarix və məbləğ, müştəri müraciətinin kateqoriyası.

**Model səhv edə bilər:** sahəni buraxa, rəqəmi mətn kimi yaza, uydura bilər. Nəticəni həmişə **validasiya** edin (Bean Validation, biznes qaydaları). Vacib qərarları (ödəniş, sifarişin təsdiqi) isə insanın təsdiqindən keçirin. Bəzi provayderlər "native structured output" / JSON mode dəstəkləyir: format zəmanətli olur, məzmun isə yenə yoxlanmalıdır.

### 3. Tool calling: model sizin kodunuzu çağırır

```java
@Tool(description = "Get an order by its numeric id: customer, product and current status")
public String getOrder(@ToolParam(description = "The order id, a number between 1 and 1000") long orderId) { ... }

@Tool(description = "Cancel an order. Only orders with status NEW or PAID can be cancelled")
public String cancelOrder(@ToolParam(description = "The order id to cancel") long orderId) { ... }

chatClient.prompt().user(question).tools(new OrderTools(store)).call().content();
```

Addım-addım:

1. Spring AI metodların adını, **təsvirini** və parametr sxemini sorğuya əlavə edir.
2. Model kod icra etmir: "`getOrder(7)` çağır" deyə cavab qaytarır.
3. Spring AI Java metodunu çağırır və nəticəni modelə geri göndərir.
4. Model yekun cavabı yazır; lazım olsa, bir neçə tool-u ardıcıl çağırır.

"7 və 4 nömrəli sifarişləri ləğv et" sualı modeli `cancelOrder(7)` və `cancelOrder(4)` çağırmağa aparır. Demo cavabla birlikdə hansı tool-un hansı arqumentlə çağırıldığını da göstərir.

**Təhlükəsizlik qaydası:** model yalnız verdiyiniz metodları çağıra bilər, amma **hansı arqumentlə** çağıracağına o qərar verir, və istifadəçi onu "başqasının sifarişini ləğv et" deməyə təhrik edə bilər. Ona görə:

- Biznes qaydaları **tool-un içindədir** (`cancelOrder` yalnız `NEW` və `PAID` sifarişləri ləğv edir; qalanları üçün izahlı imtina qaytarır).
- Real layihədə istifadəçinin həmin obyektə **icazəsi** tool-da yoxlanılır (23-cü fəsildəki IDOR).
- Dağıdıcı əməliyyatlar (pul köçürmək, silmək) üçün insan təsdiqi.
- Tool-un təsviri modelin "sənədləşməsidir": aydın və dəqiq yazılmalıdır.

`OrderTools` hər sorğu üçün yenidən yaradılır və çağırışları qeyd edir, ona görə tool-lar arasında istifadəçi konteksti qarışmır.

### 4. RAG: öz sənədlərinizdən cavab

Model bu repodakı kitabı bilmir. RAG ona lazımi hissəni **sual anında** verir:

```
start (bir dəfə, arxa fonda):
  docs/*.md ─► MarkdownDocumentReader ─► TokenTextSplitter (~350 token) ─► embedding ─► VectorStore

hər sual:
  sual ─► embedding ─► ən yaxın 4 parça (similarity search) ─► system prompt-a "Context" ─► model ─► SSE
```

**Ingestion** (`DocsIngestion`): tətbiq hazır olandan sonra virtual thread-də işləyir. Markdown başlıqlara görə bölünür, sonra ~350 token-lik parçalara kəsilir (**chunk**-lar); hər parçanın mənbə faylı metadata kimi saxlanılır. `GET /api/rag/status` vəziyyəti göstərir.

**Retrieval + generation** (`RagController`):

```java
List<Document> found = vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(4).build());
// 1. əvvəl "sources" event-i: hansı fayllardan, hansı oxşarlıq balı ilə
// 2. sonra kontekstlə birlikdə modelə:
chatClient.prompt().system(SYSTEM.formatted(context)).user(question).stream().content()...
```

```
Answer the question using only the context below, which comes from a book about
backend development with Java and Spring Boot (...). If the context does not
contain the answer, say that the book does not cover it.
Context:
%s
```

- **Mənbələri göstərmək** etibar üçün vacibdir: istifadəçi cavabın haradan gəldiyini yoxlaya bilir.
- **"Kontekstdə yoxdursa, bilmirəm de"** qaydası uydurmaları xeyli azaldır.
- **Chunk ölçüsü və `topK` balans məsələsidir:** kiçik parça dəqiq tapılır, amma konteksti az olur; çox parça isə pəncərəni doldurur və modeli çaşdırır.
- `SimpleVectorStore` yaddaşdadır və demo üçündür. Production-da PGvector, Redis, Elasticsearch, Qdrant istifadə olunur; `VectorStore` bean-ini dəyişmək kifayətdir.
- RAG burada `QuestionAnswerAdvisor` olmadan, "əl ilə" yazılıb, ki hər addım görünsün. Real layihədə `spring-ai-vector-store-advisor` asılılığındakı advisor bunu bir sətirlə edir.

**RAG ya fine-tuning?** Məlumat tez-tez dəyişirsə, mənbə göstərilməlidirsə və ya giriş hüququ istifadəçiyə görə fərqlidirsə, RAG seçilir. Fine-tuning isə üslub, format və ya çox spesifik tapşırıq üçündür, bilik əlavə etmək üçün yox.

### 5. Ollama işləmirsə: aydın xəta

`spring.ai.ollama.init.pull-model-strategy: when_missing` start zamanı modelləri yoxlayır və çatışmırsa yükləyir. Ollama ümumiyyətlə işləmirsə, tətbiq başlamır. Adi stack trace əvəzinə öz `FailureAnalyzer`-imiz nə etmək lazım olduğunu yazır:

```
APPLICATION FAILED TO START
Description: Spring AI cannot reach Ollama at http://localhost:11434 ...
Action: Start Ollama first: docker run -d --name ollama -p 11434:11434 ...
```

İşləyərkən Ollama düşsə, `AiErrors` `503` və izahlı JSON qaytarır.

### 6. Provayderi dəyişmək

```groovy
implementation 'org.springframework.ai:spring-ai-starter-model-ollama'
// ↓ əvəzinə
implementation 'org.springframework.ai:spring-ai-starter-model-anthropic'   // və ya -openai, -azure-openai, -bedrock...
```

```yaml
spring.ai.anthropic.api-key: ${ANTHROPIC_API_KEY}
```

Controller-lər, tool-lar və RAG dəyişmir. Lokal model (Ollama) və bulud modeli arasında seçim:

| | Lokal (Ollama) | Bulud API |
|---|---|---|
| Məxfilik | Məlumat kənara çıxmır | Provayderə gedir (müqaviləyə baxın) |
| Qiymət | Hardware, sorğu başına pulsuz | Token başına |
| Keyfiyyət | Kiçik modellər zəifdir (tool calling, JSON-da səhv edir) | Güclü modellər |
| İşə salmaq | GPU/RAM, model idarəsi | API açarı |

### 7. Test: modelsiz, deterministik

Real LLM testdə problemdir: pullu, yavaş və hər dəfə fərqli cavab verir. `FakeModels` real modellərin yerinə iki saxta model qoyur:

- **`FakeChatModel`** prompt-ları yadda saxlayır, cavabı funksiya ilə qaytarır və `stream`-də sözlərə bölür;
- **`BagOfWordsEmbeddingModel`** sadə "söz torbası" embedding-idir: eyni sözləri olan mətnlər yaxın düşür.

```yaml
# application-test.yml: real Ollama avtomatik konfiqurasiyasını söndürür
spring.ai.model.chat: none
spring.ai.model.embedding: none
```

Bunlarla modelsiz yoxlanılanlar:

- SSE formatı;
- yaddaşın prompt-a əlavə olunması;
- JSON-un record-a parse edilməsi;
- tool-ların məntiqi;
- RAG-da düzgün fəslin tapılması və kontekstin system prompt-a düşməsi.

Bunlar **sizin kodunuzun** testləridir. **Modelin keyfiyyəti** (cavablar düzgündürmü?) isə ayrıca yoxlanılır: sual-cavab nümunələri dəsti və avtomatik qiymətləndirmə (**eval**-lar), bəzən başqa bir modelin hakimliyi ilə (LLM-as-a-judge).

## Production-da nələrə diqqət etmək lazımdır

| Mövzu | Nə etmək |
|---|---|
| **Xərc** | Token-ləri ölçün (Spring AI observation-ları token istifadəsini metrikaya yazır); prompt-u qısaldın, uyğun model seçin, keşləyin |
| **Gecikmə** | Streaming; daha kiçik və sürətli model; paralel tool çağırışları |
| **Rate limit** | Provayderin limitləri: retry, backoff, növbə (22-ci fəsil) |
| **Prompt injection** | İstifadəçi mətni və RAG sənədləri "əvvəlki qaydaları unut" deyə bilər. Qaydalar və icazələr kodda olmalıdır, prompt-da yox; tool-lar ən az imtiyazla |
| **Şəxsi məlumat** | Modelə nə göndərildiyinə nəzarət edin (maskalama); logları qoruyun |
| **Keyfiyyət** | Eval dəsti, versiyalanmış prompt-lar, A/B test, istifadəçi rəyi (👍/👎) |
| **Observability** | Hər model çağırışı span-dır (24-cü fəsil): hansı prompt, neçə token, nə qədər vaxt |
| **Guardrail-lər** | Giriş və çıxışın yoxlanması: mövzudan kənar suallar, zərərli məzmun, format |

## Tələlər

- **Model çıxışına etibar etmək.** Structured output və tool arqumentləri validasiya olunmalıdır.
- **İcazəni prompt-a yazmaq.** "Başqasının sifarişini göstərmə" prompt-da yox, tool-un kodunda olmalıdır.
- **Yaddaşsız chat gözləmək.** Model stateless-dir, yaddaş sizin işinizdir.
- **Konteksti doldurmaq.** Çox RAG parçası və uzun tarixçə cavabı pisləşdirir və bahalaşdırır.
- **Kiçik lokal modeldən çox şey gözləmək.** 3B model tool calling və JSON-da tez-tez səhv edir.
- **SSE-də xətanı HTTP status ilə göndərmək.** Stream başlayıbsa, xəta event kimi göndərilir.
- **Testdə real model.** Pullu, yavaş və flaky testlər alınır; kodu saxta modellə, keyfiyyəti isə eval ilə yoxlayın.
- **İlk sorğu yavaşdır.** Ollama modeli ilk sorğuda yaddaşa yükləyir; bu, 10-30 saniyə çəkə bilər.

## Yadda saxla

- LLM **stateless**-dir, **token**-lərlə işləyir, və **bilmədiyini uydura bilər**.
- Spring AI provayderdən asılı olmayan interfeyslər verir: `ChatClient`, `EmbeddingModel`, `VectorStore`, `@Tool`, `ChatMemory`, advisor-lar.
- Uzun cavab **SSE + `Flux`** ilə streaming olunur; xəta isə event kimi göndərilir.
- **Structured output** mətni Java obyektinə çevirir, amma nəticə validasiya olunmalıdır.
- **Tool calling**-də model yalnız "nəyi çağır" deyir; icra və **icazə** sizin kodunuzdadır.
- **RAG** = sənədləri parçala → embedding → axtar → kontekst kimi ver; mənbələri göstər.
- Kodu saxta modellərlə, modelin keyfiyyətini isə eval-larla test edin.

## Tapşırıqlar

1. Demo-da "Adım Aynurdur", sonra "Adım nə idi?" yazın. "Yaddaşı sil" basıb yenidən soruşun. Nə dəyişdi, və niyə?
2. Structured output-a mətn verin, sonra onu əsas məlumatı olmayan mətnlə təkrarlayın (məs. şəhər yoxdur). Model `deliveryCity` üçün nə qaytardı? Bunu kodda necə yoxlayardınız?
3. Tool calling-də "999 nömrəli sifarişi ləğv et", sonra isə "bütün sifarişləri ləğv et" yazın. Hansı tool-lar və neçə dəfə çağırıldı?
4. RAG-da kitabda olmayan bir sual verin ("Python-da dekoratorlar nədir?"). Model nə cavab verdi? `sources` hadisəsindəki oxşarlıq ballarına baxın.
5. (Çətin) `CHAT_MODEL=qwen2.5:7b` ilə işə salın və eyni 5 sualı iki modeldə müqayisə edin. Kiçik sual-cavab dəsti yazıb nəticələri avtomatik yoxlayan sadə eval hazırlayın.

---

## Müsahibə sualları

Generativ AI və Spring AI üzrə ən çox verilən suallar. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın.

### LLM əsasları

<details>
<summary><b>1. LLM nədir və necə işləyir (sadə dillə)?</b></summary>

Large Language Model böyük həcmdə mətn üzərində öyrədilmiş neyron şəbəkədir (transformer arxitekturası). Verilmiş mətnin davamında **növbəti token-in ehtimalını** proqnozlaşdırır və cavabı token-token yaradır. Faktları "bilmir", mətndəki qanunauyğunluqları öyrənib. Buna görə səlis, amma bəzən yanlış cavab verə bilir.
</details>

<details>
<summary><b>2. Token nədir və niyə vacibdir?</b></summary>

Modelin emal etdiyi mətn vahididir: söz, söz hissəsi və ya simvol. İngilis dilində orta hesabla bir söz ~1.3 token-dir; Azərbaycan dili kimi aqqlütinativ dillərdə isə adətən daha çoxdur. Token vacibdir, çünki:

- **qiymət** token-lə hesablanır (giriş və çıxış ayrıca);
- **kontekst pəncərəsi** token-lə ölçülür;
- **sürət** çıxış token-lərinin sayından asılıdır.
</details>

<details>
<summary><b>3. Kontekst pəncərəsi nədir və dolanda nə olur?</b></summary>

Modelin bir sorğuda görə bildiyi maksimum token sayıdır (system prompt + tarixçə + RAG konteksti + sual + cavab). Aşılsa, sorğu xəta ilə rədd edilir və ya mətn kəsilir. Böyük pəncərədə belə "ortada itmə" (lost in the middle) problemi var: model uzun kontekstin ortasındakı məlumatı daha pis istifadə edir. Buna görə konteksti yığcam saxlamaq keyfiyyət üçün də vacibdir.
</details>

<details>
<summary><b>4. Temperature və top-p nədir?</b></summary>

Növbəti token-in seçilməsindəki təsadüfiliyi idarə edir:

- **Temperature** 0-a yaxın olanda model ən ehtimallı token-i seçir (sabit, "darıxdırıcı"); yüksək olanda isə daha müxtəlif və yaradıcı, amma səhvə daha açıq cavab verir.
- **Top-p** (nucleus sampling) yalnız ümumi ehtimalı p olan ən ehtimallı token-lər arasından seçir.

Faktlar, JSON və tool calling üçün aşağı, yaradıcı mətn üçün yüksək dəyər seçilir.
</details>

<details>
<summary><b>5. Hallucination nədir və necə azaldılır?</b></summary>

Modelin inamla, amma yanlış və ya uydurma məlumat verməsidir. Azaltmaq üçün:

- **RAG** ilə real mənbə vermək;
- "kontekstdə yoxdursa, bilmirəm de" qaydası;
- **tool calling** ilə dəqiq məlumatı sistemdən almaq;
- aşağı temperature;
- mənbələri göstərmək;
- cavabı yoxlamaq (validasiya, ikinci yoxlama).

Tam aradan qaldırmaq mümkün deyil, ona görə kritik qərarlarda insan nəzarəti lazımdır.
</details>

<details>
<summary><b>6. System, user və assistant mesajları arasında fərq nədir?</b></summary>

- **System:** modelin rolu, qaydaları və formatı; bütün söhbətə təsir edir.
- **User:** istifadəçinin sorğusu.
- **Assistant:** modelin əvvəlki cavabları.

Yaddaş üçün tarixçə bu mesajlar kimi göndərilir. Tool calling-də əlavə olaraq tool çağırışı və tool nəticəsi mesajları olur.
</details>

<details>
<summary><b>7. LLM niyə "stateless"-dir və chat yaddaşı necə qurulur?</b></summary>

Hər API çağırışı müstəqildir: model əvvəlki sorğuları saxlamır. Yaddaş tətbiqdə qurulur: söhbətin mesajları saxlanılır (yaddaşda, bazada, Redis-də) və hər yeni sorğuya əlavə olunur. Strategiyalar:

- sürüşən pəncərə (son N mesaj);
- köhnə hissənin xülasəsi;
- vacib faktların ayrıca "uzunmüddətli yaddaş"da saxlanması.

Spring AI-da `ChatMemory` + `MessageChatMemoryAdvisor`.
</details>

### Prompt və structured output

<details>
<summary><b>8. Yaxşı prompt necə yazılır?</b></summary>

- Aydın rol və tapşırıq;
- lazımi kontekst;
- çıxış formatı (nümunə ilə);
- məhdudiyyətlər ("yalnız kontekstdən", "bilmirsənsə, de");
- nümunələr (**few-shot**);
- mürəkkəb tapşırıqlarda addımlara bölmək.

Prompt-lar kod kimi versiyalanmalı və eval dəsti ilə test olunmalıdır.
</details>

<details>
<summary><b>9. Zero-shot, few-shot və chain-of-thought nədir?</b></summary>

- **Zero-shot:** nümunəsiz, yalnız təlimatla.
- **Few-shot:** prompt-a bir neçə "giriş → gözlənilən çıxış" nümunəsi əlavə etmək; format və üslubu çox yaxşılaşdırır.
- **Chain-of-thought:** modeldən cavabdan əvvəl addım-addım düşünməsini istəmək; məntiqi və riyazi tapşırıqlarda dəqiqliyi artırır, amma token və gecikmə artır.

Müasir "reasoning" modelləri bunu daxildə özləri edir.
</details>

<details>
<summary><b>10. Structured output necə işləyir və nə qədər etibarlıdır?</b></summary>

Framework hədəf tipdən (Java record) JSON Schema yaradır və prompt-a format təlimatı əlavə edir. Bəzi provayderlərdə isə native JSON/schema mode ilə sxemə uyğunluq zəmanətli olur. Cavab parse olunub obyektə çevrilir (Spring AI: `.call().entity(OrderDraft.class)`). Format bəzən pozulur (xüsusən kiçik modellərdə), məzmun isə yanlış ola bilər. Ona görə:

- parse xətasında retry;
- Bean Validation;
- biznes yoxlamaları;
- vacib qərarlarda insan təsdiqi.
</details>

### RAG

<details>
<summary><b>11. RAG nədir və nə üçündür?</b></summary>

Retrieval-Augmented Generation: sual gələndə əvvəlcə öz məlumat bazanızdan (sənədlər, wiki, müqavilələr) ona aid parçalar tapılır, sonra bu parçalar kontekst kimi prompt-a əlavə olunur və model onlara əsasən cavab verir. Faydaları:

- model sizin **təzə və daxili** məlumatınızla işləyir;
- mənbələri göstərmək mümkündür;
- uydurmalar azalır;
- məlumat yenilənəndə modeli yenidən öyrətmək lazım olmur.
</details>

<details>
<summary><b>12. RAG pipeline-ının addımları hansılardır?</b></summary>

**Ingestion** (əvvəlcədən):

1. sənədləri oxumaq (PDF, Markdown, HTML);
2. təmizləmək;
3. parçalara bölmək (**chunking**);
4. hər parçanın embedding-ini hesablamaq;
5. vektor bazasına metadata ilə yazmaq.

**Query** (hər sual):

1. sualın embedding-i;
2. oxşar parçaların axtarışı (topK, filtr);
3. istəyə görə yenidən sıralama (reranking);
4. kontekstli prompt;
5. generasiya;
6. mənbələrin göstərilməsi.
</details>

<details>
<summary><b>13. Embedding nədir və oxşarlıq necə ölçülür?</b></summary>

Mətnin mənasını əks etdirən ədəd vektorudur (yüzlərlə-minlərlə ölçü). Mənaca yaxın mətnlərin vektorları da yaxın olur, hətta sözləri fərqli olsa belə ("avtomobil" və "maşın"). Oxşarlıq adətən **cosine similarity** ilə (vektorlar arasındakı bucaq) və ya dot product/Euclidean məsafə ilə ölçülür. Sənəd və sual **eyni** embedding modeli ilə vektorlaşdırılmalıdır.
</details>

<details>
<summary><b>14. Chunking strategiyası niyə vacibdir?</b></summary>

- Parça çox böyükdürsə, axtarış qeyri-dəqiq olur və kontekst pəncərəsi dolur.
- Çox kiçikdirsə, parçada mənanı başa düşmək üçün kifayət qədər kontekst olmur.

Strategiyalar:

- sabit ölçü + üst-üstə düşmə (overlap);
- struktura görə (başlıqlar, paraqraflar; bu modulda Markdown başlıqları + ~350 token);
- semantik bölmə.

Parçaya mənbə, başlıq və tarix kimi metadata əlavə etmək axtarışı və mənbə göstərməyi yaxşılaşdırır.
</details>

<details>
<summary><b>15. Vector database nədir? Hansıları var?</b></summary>

Vektorları saxlayan və "ən yaxın N vektoru" sorğusunu sürətlə (təxmini ən yaxın qonşu indeksləri ilə: HNSW, IVF) icra edən bazadır. Metadata filtri də dəstəkləyir. Nümunələr:

- PostgreSQL + **pgvector**;
- Redis;
- Elasticsearch/OpenSearch;
- Qdrant, Weaviate, Milvus, Pinecone, Chroma.

Mövcud bazanız (PostgreSQL) varsa, pgvector çox vaxt ən sadə başlanğıcdır.
</details>

<details>
<summary><b>16. RAG-ın keyfiyyətini necə yaxşılaşdırmaq olar?</b></summary>

- Daha yaxşı chunking və metadata;
- **hybrid search:** vektor + açar söz (BM25);
- **reranking:** tapılan parçaları ayrıca modellə yenidən sıralamaq;
- sualın yenidən yazılması və genişləndirilməsi (query rewriting);
- metadata filtrləri (tarix, departament, giriş hüququ);
- düzgün `topK`;
- keyfiyyətli embedding modeli;
- **eval:** retrieval-ın düzgün parçaları tapıb-tapmadığını (recall) və cavabın kontekstə sadiqliyini (faithfulness) ayrıca ölçmək.
</details>

<details>
<summary><b>17. RAG, yoxsa fine-tuning?</b></summary>

- **RAG:** bilik əlavə etmək, tez dəyişən məlumat, mənbə göstərmək və istifadəçiyə görə giriş hüququ üçün. Ucuzdur, tez yenilənir.
- **Fine-tuning:** modelin **davranışını** (üslub, format, spesifik tapşırıq, domen terminologiyası) dəyişmək üçün. Yeni faktları etibarlı şəkildə "öyrətmək" üçün yaxşı deyil.

Çox vaxt yaxşı prompt + RAG kifayətdir; fine-tuning isə bahalı və əlavə əməliyyat yüküdür.
</details>

### Tool calling və agentlər

<details>
<summary><b>18. Tool (function) calling necə işləyir?</b></summary>

Tətbiq sorğu ilə birlikdə mövcud funksiyaların adını, təsvirini və parametr sxemini göndərir. Model lazım bildikdə mətn əvəzinə "bu funksiyanı bu arqumentlərlə çağır" cavabı qaytarır. Tətbiq funksiyanı **özü** icra edir, nəticəni modelə geri göndərir, model isə yekun cavabı yazır (lazım olsa, bir neçə dövr). Spring AI-da `@Tool` və `@ToolParam` annotasiyaları və `.tools(...)`; dövrü framework idarə edir.
</details>

<details>
<summary><b>19. Tool calling-in təhlükəsizlik riskləri nədir?</b></summary>

- Model arqumentləri **istifadəçi mətninə** əsasən seçir, və istifadəçi (və ya RAG sənədi) onu manipulyasiya edə bilər (prompt injection).
- Qorunmalar:
  - icazə yoxlaması tool-un kodunda (istifadəçi bu obyektə sahibdirmi);
  - ən az imtiyaz (yalnız lazım olan tool-lar);
  - arqumentlərin validasiyası;
  - dağıdıcı əməliyyatlar üçün insan təsdiqi;
  - rate limit;
  - audit log.
- Model heç vaxt birbaşa SQL, shell və ya sərbəst HTTP çağırışı ala bilməməlidir.
</details>

<details>
<summary><b>20. AI agent nədir? Chatbot-dan fərqi nədir?</b></summary>

Agent hədəfə çatmaq üçün **dövr** ilə işləyir: planlaşdırır, tool çağırır, nəticəyə baxır, növbəti addımı seçir, və bu, bir neçə dəfə təkrarlanır. Chatbot isə adətən bir sual → bir cavabdır. Agentlər daha güclüdür, amma:

- daha bahalı və yavaşdır;
- daha az proqnozlaşdırılandır;
- dövrün limitləri (maksimum addım, büdcə, timeout) və müşahidə (hər addımın trace-i) məcburidir.

Çox vaxt sadə, deterministik iş axını (workflow) + bir neçə LLM çağırışı "tam agent"dən daha etibarlıdır.
</details>

<details>
<summary><b>21. MCP (Model Context Protocol) nədir?</b></summary>

Anthropic-in təqdim etdiyi açıq protokoldur: AI tətbiqlərini (client) xarici tool-lara, məlumat mənbələrinə və prompt-lara (server) standart yolla qoşur. Hər inteqrasiyanı hər tətbiq üçün ayrıca yazmaq əvəzinə bir MCP server bir dəfə yazılır, və onu MCP dəstəkləyən istənilən client (IDE-lər, chat tətbiqləri, agentlər) istifadə edə bilər. Spring AI-da MCP client və server starter-ləri var: öz servisinizin əməliyyatlarını MCP server kimi açmaq və ya xarici MCP server-lərin tool-larını `ChatClient`-ə qoşmaq olur.
</details>

### Spring AI

<details>
<summary><b>22. Spring AI-ın əsas abstraksiyaları hansılardır?</b></summary>

- `ChatModel` / `StreamingChatModel`: provayder adapteri.
- `ChatClient`: fluent API (prompt, advisor-lar, tool-lar, `entity()`, `stream()`).
- `EmbeddingModel` və `VectorStore`: RAG üçün.
- `DocumentReader` / `TextSplitter`: ingestion.
- `ChatMemory`: söhbət yaddaşı.
- `@Tool`: tool calling.
- **Advisor-lar:** prompt və cavab ətrafında interceptor-lar.

Hamısı interfeysdir: provayder və vektor bazası konfiqurasiya ilə dəyişir.
</details>

<details>
<summary><b>23. <code>ChatModel</code> ilə <code>ChatClient</code> arasında fərq nədir?</b></summary>

`ChatModel` aşağı səviyyəli interfeysdir: `Prompt` alır, `ChatResponse` qaytarır; provayderin adapteridir. `ChatClient` onun üstündə fluent API-dir (`WebClient` / `RestClient` kimi): default system prompt, advisor-lar, tool-lar, structured output (`entity`), streaming (`stream().content()`) və parametrlər rahat şəkildə verilir. Tətbiq kodu adətən `ChatClient` ilə yazılır.
</details>

<details>
<summary><b>24. Advisor nədir və hansı hazır advisor-lar var?</b></summary>

`ChatClient`-in sorğu və cavab zəncirinə qoşulan interceptor-dur (Servlet filtri və ya Spring AOP-a bənzəyir). Hazır olanlar:

- `MessageChatMemoryAdvisor`: yaddaş;
- `QuestionAnswerAdvisor` (`spring-ai-vector-store-advisor`) və `RetrievalAugmentationAdvisor` (`spring-ai-rag`, modul-modul RAG: sualın yenidən yazılması, bir neçə mənbə): RAG;
- `SafeGuardAdvisor`: qadağan olunmuş məzmun;
- `SimpleLoggerAdvisor`: loglama.

Öz advisor-unuzu yazmaqla prompt-u zənginləşdirmək, cavabı yoxlamaq və ya metrika toplamaq olar.
</details>

<details>
<summary><b>25. Spring AI tətbiqini necə test etmək olar?</b></summary>

- **Kod testləri:** real model əvəzinə saxta `ChatModel` və `EmbeddingModel` (və ya mock). Deterministik, sürətli və pulsuzdur; prompt-un düzgün qurulduğu, yaddaşın əlavə olunduğu, parse və tool məntiqi yoxlanılır. Bu modulda `FakeModels` və `spring.ai.model.chat: none`.
- **İnteqrasiya:** Testcontainers ilə Ollama və kiçik model (yavaşdır, amma real).
- **Keyfiyyət:** eval dəsti; Spring AI-da `RelevancyEvaluator` və `FactCheckingEvaluator` kimi evaluator-lar var.
</details>

### Production

<details>
<summary><b>26. Prompt injection nədir və necə qorunmaq olar?</b></summary>

İstifadəçi girişi və ya xarici məzmun (RAG sənədi, veb səhifə, e-poçt) modelə "əvvəlki təlimatları unut, bunu et" kimi təlimat verir. **Indirect** injection xüsusilə təhlükəlidir: təlimat istifadəçidən yox, oxunan sənəddən gəlir. Tam həlli yoxdur; qorunma çox qatlıdır:

- icazələri və qaydaları kodda saxlamaq (prompt-da yox);
- tool-ları ən az imtiyazla vermək;
- xarici məzmunu təlimat kimi yox, məlumat kimi işarələmək;
- çıxışı yoxlamaq;
- həssas əməliyyatlarda insan təsdiqi;
- monitorinq.
</details>

<details>
<summary><b>27. LLM tətbiqinin keyfiyyətini necə ölçmək olar?</b></summary>

- Real istifadə hallarından **eval dəsti** (sual + gözlənilən cavab və ya meyarlar).
- **Avtomatik metrikalar:** dəqiq uyğunluq, JSON validliyi, RAG üçün retrieval recall və faithfulness.
- **LLM-as-a-judge** (başqa model qiymətləndirir, meyarlarla).
- İnsan qiymətləndirməsi.
- Production-da istifadəçi rəyi və A/B testlər.

Prompt, model və ya chunking dəyişəndə eval yenidən işlədilir; bu, regressiya testi kimidir.
</details>

<details>
<summary><b>28. LLM xərclərini necə azaltmaq olar?</b></summary>

- Tapşırığa uyğun ən kiçik modeli seçmək (sadə təsnifat üçün böyük model lazım deyil);
- prompt-u və konteksti qısaltmaq (az RAG parçası, tarixçənin xülasəsi);
- cavabları keşləmək (eyni və ya oxşar suallar üçün semantic cache);
- provayderin prompt caching-indən istifadə (təkrarlanan uzun system prompt ucuzlaşır);
- batch API;
- çıxış token-lərini məhdudlaşdırmaq;
- token istifadəsini metrika ilə izləmək və istifadəçi üzrə limit qoymaq.
</details>

<details>
<summary><b>29. LLM çağırışlarında gecikməni necə idarə etmək olar?</b></summary>

- **Streaming:** ilk token tez gəlir, istifadəçi gözləmədiyini hiss edir.
- Daha kiçik və sürətli model.
- Qısa prompt və çıxış.
- Paralel tool çağırışları.
- Keş.
- Timeout və fallback (başqa model və ya sadə cavab).
- Asinxron emal: uzun tapşırıqlar üçün "hazır olanda xəbər veririk".

Metrikalar: ilk token-ə qədər vaxt (TTFT) və saniyədə token.
</details>

<details>
<summary><b>30. LLM-ə şəxsi və ya məxfi məlumat göndərməkdə nələrə diqqət etmək lazımdır?</b></summary>

- Provayderin məlumat siyasəti: öyrənmədə istifadə olunurmu, harada saxlanılır, nə qədər müddət.
- Hüquqi tələblər (GDPR, yerli qanunvericilik, bank sirri).
- Göndərməzdən əvvəl şəxsi məlumatların maskalanması və ya anonimləşdirilməsi.
- Ən az məlumat prinsipi.
- Logların və prompt tarixçəsinin qorunması.
- RAG-da istifadəçinin giriş hüququna görə filtr: başqasının sənədi kontekstə düşməməlidir.
- Çox həssas məlumat üçün lokal və ya öz infrastrukturunuzda işləyən model (bu modulda Ollama).
</details>

---

[← 24. Observability](24-observability.md) · [Mündəricat](README.md)
