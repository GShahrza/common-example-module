# spring-ai: Spring AI + lokal LLM (Ollama)

Spring AI 2.0 ilə generativ AI-ın backend tərəfində ən çox istifadə olunan 4 üsulu. Model sizin kompüterinizdə, [Ollama](https://ollama.com)-da işləyir: API açarı lazım deyil, pulsuzdur, məlumat kənara çıxmır.

| # | Nümunə | Endpoint | Harada lazımdır |
|---|---|---|---|
| 1 | Streaming chat + yaddaş | `GET /api/chat/stream` (SSE) | ChatGPT-yə bənzər chat, cavab token-token gəlir |
| 2 | Structured output | `POST /api/extract` | Sərbəst mətndən Java obyekti: e-poçtdan sifariş, CV-dən məlumat |
| 3 | Tool (function) calling | `POST /api/tools/ask` | Model sizin Java metodlarınızı çağırır: "7 nömrəli sifariş haradadır?" |
| 4 | RAG | `GET /api/rag/stream` (SSE) | Öz sənədlərinizdən cavab: daxili wiki, müqavilələr, FAQ |

## İşə salma qaydası

Aşağıdakı addımları **ardıcıllıqla** icra edin.

### 1. Ollama-nı işə salın

Docker ilə (tövsiyə olunur):

```bash
docker run -d --name ollama -p 11434:11434 -v ollama:/root/.ollama ollama/ollama
```

Və ya Ollama-nı birbaşa quraşdırın (https://ollama.com/download); o, özü `11434` portunda işləyəcək.

Yoxlamaq üçün `curl http://localhost:11434` əmrini işlədin. Cavab `Ollama is running` olmalıdır.

### 2. Modelləri yükləyin (bir dəfə)

```bash
docker exec ollama ollama pull qwen2.5:3b         # chat modeli, ~1.9 GB
docker exec ollama ollama pull nomic-embed-text   # embedding modeli (RAG üçün), ~270 MB
```

Ollama-nı Docker-siz quraşdırmısınızsa, eyni əmrləri `docker exec ollama` olmadan işlədin: `ollama pull qwen2.5:3b`.

Bu addımı keçsəniz də olar: tətbiq ilk dəfə başlayanda çatışmayan modelləri özü yükləyir (`pull-model-strategy: when_missing`). Amma o zaman ilk start bir neçə dəqiqə çəkir.

### 3. Tətbiqi işə salın

Java 21 ilə, repo kökündən:

```bash
./gradlew :spring-ai:bootRun
```

və ya Docker ilə:

```bash
docker build -f spring-ai/Dockerfile -t spring-ai .
docker run --rm -p 8083:8083 --add-host=host.docker.internal:host-gateway spring-ai
```

Docker konteynerinin içində `localhost` konteynerin özüdür. Ona görə image Ollama-ya `host.docker.internal:11434` ünvanı ilə müraciət edir; bu dəyəri `OLLAMA_BASE_URL` dəyişəni ilə dəyişə bilərsiniz.

### 4. Brauzerdə açın

**http://localhost:8083**: hər 4 nümunə üçün düymələri olan demo səhifə.

Ollama işləmirsə, tətbiq başlamır və nəyi etmək lazım olduğunu açıq yazır:

```
APPLICATION FAILED TO START
Description: Spring AI cannot reach Ollama at http://localhost:11434 ...
Action: Start Ollama first: docker run -d --name ollama -p 11434:11434 ...
```

### Konfiqurasiya

| Dəyişən | Default | Nə üçün |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama ünvanı |
| `CHAT_MODEL` | `qwen2.5:3b` | Daha güclü kompüterdə `qwen2.5:7b` və ya `llama3.1:8b` yaxşı nəticə verir |
| `EMBEDDING_MODEL` | `nomic-embed-text` | RAG üçün mətnləri vektora çevirən model |

Məsələn: `CHAT_MODEL=llama3.1:8b ./gradlew :spring-ai:bootRun`.

Modeli dəyişmək üçün koda toxunmaq lazım deyil. OpenAI, Anthropic və ya Azure-a keçmək üçün `spring-ai-starter-model-ollama` asılılığını uyğun starter ilə əvəz edib API açarını vermək kifayətdir, çünki kod yalnız `ChatClient` və `VectorStore` interfeyslərindən istifadə edir.

---

## Necə işləyir

### Əsas anlayışlar

- **ChatModel** konkret provayderin (Ollama, OpenAI...) adapteridir. **ChatClient** onun üstündə fluent API-dir: `chatClient.prompt().user("...").call().content()`.
- **Prompt** mesajlardan ibarətdir: `system` (modelə rol və qaydalar), `user` (sual), `assistant` (modelin əvvəlki cavabları).
- **Token**: model mətni sözlə yox, token-lərlə (söz parçaları) yaradır. Stream zamanı hər hadisə adətən bir token-dir.
- **Advisor** isə prompt modelə getməzdən əvvəl və cavab qayıdandan sonra işləyən interceptor-dur (Servlet filter-ə bənzəyir). Yaddaş və RAG adətən advisor ilə qurulur.
- **Embedding**: mətni ədəd vektoruna çevirmək. Mənaca yaxın mətnlərin vektorları da yaxın olur.

### 1. Streaming chat və yaddaş

LLM cavabı 5-20 saniyə çəkə bilər. İstifadəçi bu müddəti boş ekrana baxaraq gözləməsin deyə cavab SSE ilə hissə-hissə göndərilir (bax: [kitabın 2-ci fəsli](../docs/02-sse.md)):

```java
return chatClient.prompt()
        .user(message)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
        .stream()
        .content()                                   // Flux<String>, hər element bir token
        .map(token -> event("token", Map.of("text", token)));
```

Model **yaddaşsızdır**: hər request-də ona bütün söhbət yenidən göndərilməlidir. Bunu `MessageChatMemoryAdvisor` edir. O, `conversationId` üzrə son 20 mesajı saxlayır (`MessageWindowChatMemory`) və hər yeni prompt-a əlavə edir. Testdə bunu yoxlayırıq: ikinci sualda modelə gedən prompt birinci mesajı da ehtiva edir.

Spring MVC controller-i `Flux` qaytara bilər. Bunun üçün reactor-core classpath-da olmalıdır; Spring AI onu gətirir.

### 2. Structured output

```java
OrderDraft draft = chatClient.prompt().user(text).call().entity(OrderDraft.class);
```

Spring AI `OrderDraft` record-undan JSON Schema yaradır və prompt-a "cavabı bu sxemdə JSON kimi ver" təlimatını əlavə edir, sonra cavabı Jackson ilə parse edir. Kiçik modellər bəzən sxemdən kənara çıxır, ona görə nəticəni həmişə validasiya edin.

### 3. Tool calling

```java
@Tool(description = "Returns the current status of an order by its id")
public String getOrder(@ToolParam(description = "Order id") long orderId) { ... }

chatClient.prompt().user(question).tools(new OrderTools(store)).call().content();
```

Addım-addım:

1. Spring AI metodların adını, təsvirini və parametr sxemini modelə göndərir.
2. Model özü kod icra etmir. O, "`getOrder(7)` çağır" deyə cavab qaytarır.
3. Spring AI Java metodunu çağırır və nəticəni modelə geri göndərir.
4. Model yekun cavabı yazır. Lazım olsa, bir neçə tool-u ardıcıl çağırır.

Təhlükəsizlik qaydası: model yalnız sizin verdiyiniz metodları çağıra bilər, amma **hansı arqumentlə** çağıracağına o qərar verir. Ona görə yoxlamalar tool-un içində olmalıdır. Bu nümunədə `cancelOrder` yalnız `NEW`/`PAID` sifarişləri ləğv edir; `SHIPPED` sifariş üçün imtina mətni qaytarır. Real layihədə istifadəçinin həmin sifarişə icazəsi olub-olmadığını da tool-da yoxlayın. `OrderTools` hər request üçün yenidən yaradılır və hansı tool-un çağırıldığını qeyd edir. Bu siyahı cavabla birlikdə qaytarılır və demo səhifədə görünür.

### 4. RAG (Retrieval-Augmented Generation)

Model bu repodakı kitabı bilmir. RAG ona lazımi hissəni sual anında verir:

```
start:  docs/*.md  →  MarkdownDocumentReader  →  TokenTextSplitter (~350 token)  →  embedding  →  VectorStore
sual:   sual → embedding → ən yaxın 4 parça → system prompt-a "Context" kimi → model → SSE
```

- **Ingestion** (`DocsIngestion`): tətbiq hazır olandan sonra arxa fonda (virtual thread-də) işləyir. Vəziyyəti `GET /api/rag/status` göstərir.
- **Retrieval + generation** (`RagController`): əvvəl `sources` hadisəsi göndərilir. O, tapılan parçaların hansı fayldan olduğunu və oxşarlıq balını göstərir; bunun ardınca token-lər gəlir. Cavabın hansı mənbəyə əsaslandığını göstərmək istifadəçi etibarı üçün vacibdir.
- System prompt modelə "yalnız kontekstdən cavab ver, orada yoxdursa, bilmirəm de" deyir. Bu, uydurmaları (hallucination) xeyli azaldır.

`SimpleVectorStore` yaddaşda saxlanan, demo üçün uyğun vektor bazasıdır. Production-da PGvector, Redis, Elasticsearch və ya Qdrant istifadə olunur. Onlara keçmək üçün yalnız `VectorStore` bean-ini dəyişmək kifayətdir.

RAG-ı burada `QuestionAnswerAdvisor` olmadan, "əl ilə" yazmışıq ki, hər addım görünsün. Real layihədə `spring-ai-vector-store-advisor` asılılığındakı `QuestionAnswerAdvisor` eyni işi bir sətirlə görür.

## Testlər

Testlər Ollama olmadan işləyir. `FakeModels` real modellərin yerinə iki saxta model qoyur:

- `FakeChatModel` prompt-ları yadda saxlayır, cavabı sözlərə bölüb stream edir;
- `BagOfWordsEmbeddingModel` sadə "söz torbası" embedding-idir.

Beləliklə, SSE formatı, yaddaşın prompt-a əlavə olunması, JSON-un record-a parse edilməsi, tool-ların məntiqi və RAG-da düzgün fəslin tapılması modelsiz, sürətli və deterministik yoxlanılır.

```bash
./gradlew :spring-ai:test
```

## Tələlər

- **İlk cavab gecikir.** Ollama modeli ilk request-də yaddaşa yükləyir; bu, 10-30 saniyə çəkə bilər.
- **Kiçik model zəif cavab verir.** `qwen2.5:3b` sürətlidir, amma tool calling və JSON-da səhv edə bilər. RAM imkan verirsə, 7B/8B modellərdən istifadə edin.
- **Kontekst limiti.** RAG-da çox parça göndərsəniz, modelin kontekst pəncərəsi dolur və cavab pisləşir. `topK` və chunk ölçüsü balanslaşdırılmalıdır.
- **Prompt injection.** İstifadəçi mətni və RAG sənədləri modelə "təlimat" verə bilər. Tool-larda icazəni həmişə kodda yoxlayın, modelin sözünə etibar etməyin.
