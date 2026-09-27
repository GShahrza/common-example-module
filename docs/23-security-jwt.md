# 23. Spring Security və JWT: API-ni stateless qorumaq

[← 22. Resilience4j](22-resilience.md) · [Mündəricat](README.md) · Növbəti: [24. Observability →](24-observability.md)

**Hissə IV: Əlavə mövzular** · **Kod:** [`SecurityConfig`](../security-jwt/src/main/java/io/github/gshahrza/security/SecurityConfig.java), [`TokenService`](../security-jwt/src/main/java/io/github/gshahrza/security/token/TokenService.java), [`JwtKeyConfig`](../security-jwt/src/main/java/io/github/gshahrza/security/token/JwtKeyConfig.java), [`RefreshTokenStore`](../security-jwt/src/main/java/io/github/gshahrza/security/token/RefreshTokenStore.java), [`AuthController`](../security-jwt/src/main/java/io/github/gshahrza/security/auth/AuthController.java), [`OrderController`](../security-jwt/src/main/java/io/github/gshahrza/security/api/OrderController.java) · **Demo:** http://localhost:8090

```bash
./gradlew :security-jwt:bootRun                        # aynur / aynur123, admin / admin123
JWT_ACCESS_TTL=30s ./gradlew :security-jwt:bootRun     # avtomatik refresh-i real vaxtla görmək üçün
```

---

## Həyatdan analogiya

Konfrans binası. Girişdə **qeydiyyat masası** var: pasportunuzu göstərirsiniz, sizi siyahıda tapırlar və boynunuza **bədc** asırlar.

- Bədcdə adınız, rolunuz ("iştirakçı", "spiker") və **bitmə vaxtı** yazılıb, üstündə isə təşkilatçının **möhürü** var.
- İçəridə hər zalın qapısındakı mühafizəçi siyahıya baxmır və qeydiyyata zəng etmir: **möhürü və vaxtı** yoxlayır, rolunuza baxır və buraxır (və ya buraxmır).
- Bədcdəki yazını qələmlə "spiker"ə dəyişsəniz, möhür uyğun gəlmir: mühafizəçi bunu dərhal görür.
- Bədc axşam **bitir**. Səhər təzəsini almaq üçün pasportu yenidən göstərmirsiniz: əlinizdəki **qəbzi** masaya verirsiniz, sizə yeni bədc və yeni qəbz verilir. Köhnə qəbz artıq etibarsızdır.
- Kimsə köhnə qəbzinizi oğurlayıb gətirsə, masa görür ki, bu qəbz artıq istifadə olunub və **sizin bütün bədclərinizi** ləğv edir.

Pasportu göstərmək **login**, bədc **access token (JWT)**, möhür **imza**, mühafizəçi **resource server**, qəbz isə **refresh token**-dir. Köhnə qəbzin aşkarlanması **reuse detection**-dır.

## Problem

Mobil tətbiq, SPA və digər servislər API-ni çağırır. Hər sorğuda bilmək lazımdır:

1. **Kimdir?** (autentifikasiya)
2. **Bunu etməyə icazəsi varmı?** (avtorizasiya)

Klassik veb tətbiqdə bu, **sessiya** ilə həll olunur: server login-dən sonra sessiya yaradır, brauzer onun id-sini cookie-də göndərir. API-lər üçün isə bu yanaşmanın problemləri var:

- **Mobil tətbiqlər** cookie ilə işləməyi sevmir; `Authorization` header-i təbii yoldur.
- **Bir neçə instance və servis** varsa, sessiya paylaşılmalıdır (Redis, sticky session).
- Hər sorğu sessiya anbarına bir **lookup** edir.
- Başqa servislər istifadəçini tanımaq üçün **sizin** sessiya anbarınıza müraciət etməlidir.

**JWT** bunun alternatividir: server istifadəçinin kim olduğunu və nə edə biləcəyini imzalı token-ə yazır. Token-i hər servis **öz başına**, bazasız və şəbəkəsiz yoxlaya bilir.

## Autentifikasiya, avtorizasiya, 401, 403

| | Sual | Uğursuz olsa |
|---|---|---|
| **Autentifikasiya** | Sən kimsən? | **401 Unauthorized**: token yoxdur, səhvdir və ya vaxtı bitib. Klient refresh edir və ya login ekranına qayıdır |
| **Avtorizasiya** | Buna icazən var? | **403 Forbidden**: səni tanıyıram, amma icazən yoxdur. Refresh kömək etməz |

İkisi də standart `WWW-Authenticate: Bearer error="..."` header-ini (RFC 6750) qaytarır. Modul əlavə olaraq mobil və SPA klientlər üçün JSON body də qaytarır.

## JWT-nin quruluşu

```
eyJraWQiOiI5MGRk...  .  eyJzdWIiOiJheW51ciIs...  .  kT8x2Vb...
      header                   payload                  imza
```

```json
{ "kid": "90dd7864-...", "alg": "RS256" }
{ "sub": "aynur", "roles": ["USER"], "scope": "orders:read orders:write",
  "iss": "http://localhost:8090", "iat": 1790489221, "exp": 1790490121, "jti": "4bc4..." }
```

| Claim | Mənası |
|---|---|
| `sub` | Kimdir (istifadəçi id-si) |
| `iss` | Token-i kim verib; resource server yalnız etibar etdiyi issuer-i qəbul edir |
| `exp`, `iat` | Bitmə və verilmə vaxtı |
| `jti` | Token-in unikal id-si (loglar, qara siyahı) |
| `aud` | Token kim üçündür (bir neçə API olanda vacibdir) |
| `roles`, `scope` | Öz claim-lərimiz: kim olduğu və token-in nəyə icazəsi olduğu |

İki vacib fakt:

1. **Payload şifrələnmir.** O sadəcə Base64-dür; token-i əldə edən hər kəs onu oxuya bilər (demo səhifədə görünür). İçinə parol, kart nömrəsi və ya şəxsi məlumat qoyulmur.
2. **İmza dəyişikliyi aşkarlayır.** Demo-da "Payload-da rolu ADMIN et" düyməsi rolu dəyişdirir, amma imza köhnə qalır: server `401 invalid_token` qaytarır (testdə yoxlanılır).

## Həll, addım-addım

### 1. Login: token vermək

```java
@PostMapping("/api/auth/login")
ResponseEntity<TokenResponse> login(@Valid @RequestBody Login login, @RequestHeader(USER_AGENT) String device) {
    Authentication auth = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(login.username(), login.password()));
    String refreshToken = refreshTokens.issue(auth.getName(), device);
    return respond(tokens.accessToken(auth.getName(), auth.getAuthorities()), refreshToken, ...);
}
```

```json
{ "access_token": "eyJ...", "token_type": "Bearer", "expires_in": 900, "refresh_token": "Qm9..." }
```

- Parollar **BCrypt** hash-i kimi saxlanılır (`PasswordEncoderFactories.createDelegatingPasswordEncoder()`, `{bcrypt}$2a$...`). BCrypt qəsdən yavaşdır: sızmış hash-ləri sındırmaq çox baha başa gəlir.
- "Belə istifadəçi yoxdur" və "parol səhvdir" üçün **eyni** cavab qaytarılır. Yoxsa hücumçu mövcud istifadəçi adlarını tapa bilər (testdə yoxlanılır).
- Cavabın sahə adları OAuth 2.0-dakı kimidir (`access_token`, `expires_in`), ona görə standart client kitabxanaları onu başa düşür.

### 2. İmza: RS256 və JWKS

```java
@Bean
JwtEncoder jwtEncoder(RSAKey rsaKey) {                     // private açar: yalnız bu servisdə
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
}

@Bean
JwtDecoder jwtDecoder(RSAKey rsaKey, JwtProperties properties) {   // public açar: hər kəsdə ola bilər
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(properties.clockSkew()),        // exp, nbf (30 s tolerantlıq)
            new JwtIssuerValidator(properties.issuer())));            // iss
    return decoder;
}
```

- **HS256:** bir gizli açar həm imzalayır, həm də yoxlayır. Token-i yoxlayan hər servis bu açarı bilməlidir, deməli, o servis token **yarada** da bilər.
- **RS256:** private açar yalnız token verəndədir. Digər servislər **public** açarı `/.well-known/jwks.json`-dan götürüb yoxlayır. Başqa servisi qoşmaq üçün bir sətir kifayətdir:

```yaml
spring.security.oauth2.resourceserver.jwt.jwk-set-uri: http://auth-service/.well-known/jwks.json
```

- **`kid`** (key id) açar rotasiyası üçündür: yeni açar əlavə olunur, köhnə token-lər bitənə qədər köhnə açar da JWKS-də qalır.
- Test: başqa açarla imzalanmış "admin" token-i `401` alır; JWKS-də private açar hissələri (`d`, `p`, `q`) yoxdur.

### 3. Hər sorğunun yolu

```
Authorization: Bearer eyJ...
      │
BearerTokenAuthenticationFilter
      │  JwtDecoder: imza ✓  exp ✓  iss ✓           ✗ → 401 + WWW-Authenticate: Bearer error="invalid_token"
      ▼
JwtAuthenticationConverter: "scope" → SCOPE_*, "roles" → ROLE_*
      ▼
SecurityContext  (Authentication = JwtAuthenticationToken)
      ▼
authorizeHttpRequests: /api/admin/** → hasRole("ADMIN")   ✗ → 403 insufficient_scope
      ▼
@PreAuthorize("#username == authentication.name or hasRole('ADMIN')")
      ▼
controller: @AuthenticationPrincipal Jwt jwt
```

```java
http
    .csrf(csrf -> csrf.disable())                                          // cookie yoxdur, CSRF riski yoxdur
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/.well-known/jwks.json").permitAll()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/orders").hasAuthority("SCOPE_orders:write")
            .anyRequest().authenticated())                                // default: bağlı
    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(authoritiesFromJwt())));
```

`anyRequest().authenticated()` **deny by default** prinsipidir: yeni endpoint unudulsa belə, public olmur.

Spring Security 7-də iki yenilik görünür:

- `/api/me`-nin qaytardığı authority-lər arasında `FACTOR_BEARER` var: hər autentifikasiya üsulu "factor" kimi qeyd olunur (MFA qaydaları üçün).
- 401 cavabında `resource_metadata` var (RFC 9728), və `/.well-known/oauth-protected-resource` endpoint-i avtomatik açılır.

### 4. Rol, scope və sahiblik

- **Rol** istifadəçinin **kim** olduğudur (`ADMIN`).
- **Scope** isə bu **token-in nəyə icazəsi** olduğudur (`orders:write`).

Fərq üçüncü tərəf tətbiqlərdə görünür: istifadəçi admin ola bilər, amma bir hesabat tətbiqinə yalnız `orders:read` icazəsi verir.

```java
static Converter<Jwt, Collection<GrantedAuthority>> authorities() {
    JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();   // "scope" → SCOPE_x
    return jwt -> {
        Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
        jwt.getClaimAsStringList("roles").forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        return authorities;
    };
}
```

`hasRole("ADMIN")` əslində `ROLE_ADMIN` authority-sini axtarır; prefiksi buna görə əlavə edirik.

**Ən çox rast gəlinən API boşluğu IDOR-dur** (OWASP API Security Top 10-da 1-ci yer: Broken Object Level Authorization):

```java
@GetMapping("/api/users/{username}/orders")
@PreAuthorize("#username == authentication.name or hasRole('ADMIN')")
List<Order> ordersOf(@PathVariable String username)
```

Login olmaq **başqasının** məlumatına baxmaq icazəsi demək deyil. `aynur` URL-də `rashad` yazsa, `403` almalıdır (testdə yoxlanılır). Qayda: sahibi URL-dən və ya body-dən yox, **token-dən** götürün (`/api/orders` belə edir). URL-də id varsa, sahibliyi yoxlayın.

### 5. Refresh token

Access token qısa (15 dəq) olmalıdır, çünki onu geri almaq olmur. İstifadəçinin hər 15 dəqiqədən bir login olmaması üçün isə uzunömürlü **refresh token** verilir.

| | Access token | Refresh token |
|---|---|---|
| Format | JWT (imzalı, oxuna bilən) | Təsadüfi 256 bit |
| Ömür | 15 dəq | 7 gün istifadəsiz, aktiv login ən çox 30 gün |
| Yoxlama | Hər sorğuda, bazasız | Yalnız `/api/auth/refresh`-də, bazada |
| Geri almaq | Olmur (bitənə qədər etibarlıdır) | Olur (sətri silmək kifayətdir) |

Server tərəfdə:

- Bazada token-in özü yox, **SHA-256 hash-i** saxlanılır.
- **Rotation:** hər refresh yeni refresh token qaytarır, köhnəsi `used_at` ilə işarələnir.
- **Reuse detection:** istifadə olunmuş token yenidən gəlirsə, onu kimsə kopyalayıb. O login-in (family) bütün token-ləri silinir.
- **Atomarlıq:** yoxlama və işarələmə bir SQL-dədir, ona görə 8 paralel refresh-dən yalnız biri uğurlu olur (testdə yoxlanılır):

```sql
UPDATE refresh_token SET used_at = :now WHERE token_hash = :hash AND used_at IS NULL AND expires_at > :now
```

- **`@Transactional(noRollbackFor = InvalidRefreshTokenException.class)`:** reuse aşkarlananda family silinir **və** exception atılır. Adi `@Transactional` exception-da `DELETE`-i geri qaytarardı və oğurlanmış login işləməyə davam edərdi.
- Refresh zamanı rollar **bazadan yenidən oxunur**: rolu alınmış istifadəçi onu 15 dəqiqədən artıq saxlamır.
- Refresh token-lər bazada olduğu üçün tətbiq restart olunanda (və RSA açarı yenidən yaradılanda) klient 401 alır, refresh edir və işləməyə davam edir. Bu, modulda yoxlanılıb.

Brauzerdə refresh token **`HttpOnly` cookie**-də saxlanılır (`"cookie": true` ilə login):

```http
Set-Cookie: refresh_token=Qm9...; Path=/api/auth; Max-Age=604800; Secure; HttpOnly; SameSite=Strict
```

- `HttpOnly`: JavaScript onu görmür, ona görə XSS ilə oğurlana bilməz.
- `Path=/api/auth`: cookie yalnız auth endpoint-lərinə gedir.
- `SameSite=Strict`: başqa saytdan başlanan sorğuya əlavə olunmur, bu da `/refresh`-i CSRF-dən qoruyur.

Access token isə JS yaddaşındadır; səhifə yenilənəndə refresh cookie ilə sessiya bərpa olunur. Mobil tətbiqlər refresh token-i body-də alır və Keychain / Keystore-da saxlayır.

### 6. Klientdə avtomatik refresh

Qayda: **401 → refresh → sorğunu bir dəfə təkrarla; refresh də 401 verirsə → login ekranı.**

Tələ paralel sorğulardadır. Ekran açılanda 5 sorğu eyni anda 401 alır. Hər biri ayrıca refresh etsə, ikinci refresh artıq istifadə olunmuş token göndərir, reuse detection işə düşür və istifadəçi çıxarılır. Ona görə **refresh bir dəfə edilir, qalanları onun nəticəsini gözləyir** (single flight):

```js
let refreshing = null;
function refresh() {
  refreshing ??= fetch('/api/auth/refresh', { method: 'POST' })
      .then(r => r.ok ? r.json() : Promise.reject())
      .then(json => { access = json.access_token; return true; }, () => false)
      .finally(() => refreshing = null);
  return refreshing;
}
```

Demo-da yoxlanılıb: 5 paralel sorğu → 5 × 401 → **1** refresh → 5 × 200. Android (OkHttp `Authenticator`) və iOS (`actor`) variantları modulun [README-sindədir](../security-jwt/README.md#klientdə-avtomatik-refresh).

### 7. Logout: stateless-in qiyməti

Logout refresh token-i ləğv edir. **Access token isə bitənə qədər (maks. 15 dəq) etibarlı qalır.** Bu pəncərə qəbuledilməzdirsə, variantlar:

- daha qısa access token (5 dəq);
- `jti` qara siyahısı (Redis-də, TTL = token-in qalan ömrü): hər sorğuda bir lookup;
- opaque token + introspection: hər sorğuda auth server-ə sual verilir, yəni stateful olur.

Demo-dakı "Aktiv sessiyalar" bölməsi hər login-i (cihazı) göstərir və birini çıxarmağa imkan verir: bu, məsələn, itmiş telefon üçündür.

### 8. Testlər: iki üsul

```java
// Real token-lərlə (SecurityJwtTest, RefreshTokenTest): bütün zəncir
String forged = header + "." + base64(payload.replace("[\"USER\"]", "[\"USER\",\"ADMIN\"]")) + "." + signature;
assertThat(get("/api/admin/sessions", forged).getStatusCode().value()).isEqualTo(401);

// jwt() ilə (MethodSecurityTest): imzasız, yalnız qaydalar
mvc.perform(post("/api/orders").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders:read"))))
   .andExpect(status().isForbidden());
```

Modulda 20 test var:

- 401 və 403 cavabları;
- istifadəçi adlarının sızmaması;
- IDOR qorunması;
- dəyişdirilmiş payload, vaxtı bitmiş token və başqa açarla imzalanmış token;
- refresh rotation və reuse detection;
- cookie atributları;
- paralel refresh;
- aktiv sessiyalar və hər iki limit (7 gün, 30 gün).

## Production-da: öz auth server-inizi yazmayın

Bu modul token verməni **öyrənmək** üçün özü edir. Real sistemdə token-ləri ayrıca **authorization server** verir:

- Keycloak, Auth0, Okta, Azure AD, Cognito;
- və ya Spring Authorization Server.

Onlar bunları hazır təqdim edir: login səhifəsi, MFA, sosial login, parol sıfırlama, açar rotasiyası, audit, və mobil tətbiqlər və SPA üçün **Authorization Code + PKCE** axını. Sizin API-niz isə yalnız **resource server** olur:

```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri: https://auth.example.az/realms/shop
```

Bu bir sətir kifayətdir: Spring issuer-in `/.well-known/openid-configuration`-ından JWKS ünvanını tapır, açarları yükləyir və `iss`-i yoxlayır. Bu fəsildəki avtorizasiya qaydaları (`SecurityConfig`, `@PreAuthorize`) dəyişmədən qalır.

## Tələlər

- **`alg: none` və alqoritm qarışıqlığı.** Token-in header-inə etibar edib alqoritmi oradan seçməyin. `NimbusJwtDecoder.withPublicKey(...)` yalnız RS256 qəbul edir.
- **`localStorage`-da token.** XSS ilə oxunur. Access token yaddaşda, refresh token isə `HttpOnly` cookie-də saxlanılmalıdır.
- **Uzun access token.** 24 saatlıq token oğurlansa, 24 saat işləyir.
- **Token-də məxfi məlumat.** Payload hər kəs üçün oxunaqlıdır.
- **`aud` yoxlanılmır.** Bir API üçün verilmiş token başqa API-də də qəbul olunur. Bir neçə API varsa, `JwtClaimValidator` ilə `aud`-u yoxlayın.
- **Token-i loglamaq və URL-də göndərmək** (`?token=`): loglara, proxy loglarına və brauzer tarixçəsinə düşür.
- **IDOR:** login olmaq sahiblik demək deyil.
- **Paralel refresh:** klientdə single flight olmasa, rotation istifadəçini çıxarır.
- **Yeni refresh token-i saxlamamaq:** rotation-dan sonra köhnə token işləmir.
- **Login-də brute force:** parol cəhdlərini IP və istifadəçi üzrə məhdudlaşdırın (rate limiter, 22-ci fəsil), müvəqqəti bloklayın.

## Yadda saxla

- **Autentifikasiya** "kimsən?" (401), **avtorizasiya** "icazən var?" (403) sualıdır.
- JWT-nin payload-u **oxunaqlıdır**, imza isə onu **dəyişdirilməz** edir.
- RS256 + JWKS: private açar bir yerdə qalır, digər servislər public açarla yoxlayır.
- Resource server hər sorğuda imzanı, `exp`-i, `iss`-i (və lazım olsa `aud`-u) yoxlayır; bazaya getmir.
- Rol "kim", scope "nə edə bilər"; sahibliyi (IDOR) ayrıca yoxlayın; default "bağlı"dır.
- Qısa access token + rotation-lu, bazada hash kimi saxlanan refresh token; brauzerdə `HttpOnly` cookie; klientdə single flight.
- Production-da token-ləri auth server verir, sizin servisiniz yalnız resource server olur.

## Tapşırıqlar

1. Demo-da `aynur` ilə login olun və token-i [jwt.io](https://jwt.io)-ya yapışdırın. Payload-da nə görürsünüz? İmza niyə "invalid" görünür, və bu, niyə problem deyil?
2. "Payload-da rolu ADMIN et" düyməsini basın. `WWW-Authenticate` header-ində hansı xəta var?
3. `JWT_ACCESS_TTL=30s` ilə işə salın, login olun və 2 dəqiqə gözləyin. "GET /api/me" basın: sorğular cədvəlində hansı ardıcıllığı görürsünüz?
4. Mobil rejimdə login olun, "Əl ilə refresh", sonra "Köhnə refresh token-i yenidən işlət" basın. Niyə yeni token da işləmir?
5. (Çətin) `aud` yoxlaması əlavə edin: token-ə `aud: orders-api` yazın və decoder-ə `JwtClaimValidator` qoyun. Başqa `aud` ilə token yaradan test yazın.

---

## Müsahibə sualları

Ən çox verilən Spring Security, JWT və OAuth sualları. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın.

### Əsaslar

<details>
<summary><b>1. Autentifikasiya ilə avtorizasiya arasında fərq nədir? 401 və 403?</b></summary>

- **Autentifikasiya** kimliyin təsdiqidir ("sən kimsən?"): parol, token, sertifikat.
- **Avtorizasiya** icazənin yoxlanmasıdır ("bunu edə bilərsən?"): rol, scope, sahiblik.

**401 Unauthorized** (adına baxmayaraq) autentifikasiya yoxdur və ya etibarsızdır deməkdir; klient yenidən login olmalı və ya token-i yeniləməlidir. **403 Forbidden** isə kimlik məlumdur, amma icazə yoxdur; yenidən login kömək etmir.
</details>

<details>
<summary><b>2. Session-based və token-based autentifikasiya arasında fərq nədir?</b></summary>

- **Session:** server login-dən sonra vəziyyəti özündə saxlayır, klient yalnız session id-ni (cookie) göndərir. Logout asandır (sessiyanı silmək), amma sessiya anbarı paylaşılmalıdır və CSRF qorunması lazımdır.
- **Token (JWT):** vəziyyət imzalı token-in içindədir, server heç nə saxlamır. Miqyaslanma və servislər arası istifadə asandır, amma token-i vaxtından əvvəl ləğv etmək çətindir.

Server-də render olunan veb tətbiq üçün session, API, mobil və mikroservislər üçün token daha uyğundur.
</details>

<details>
<summary><b>3. JWT nədir və hansı hissələrdən ibarətdir?</b></summary>

JSON Web Token (RFC 7519) üç Base64URL hissədən ibarətdir və nöqtə ilə ayrılır:

- **header:** alqoritm (`alg`) və açar id-si (`kid`);
- **payload:** claim-lər (`sub`, `iss`, `exp`, `iat`, `aud`, `jti` və öz claim-ləriniz);
- **signature:** header və payload-un imzası.

Server imzanı yoxlayaraq token-in onun etibar etdiyi tərəfindən verildiyini və dəyişdirilmədiyini bilir.
</details>

<details>
<summary><b>4. JWT şifrələnibmi? JWS ilə JWE arasında fərq nədir?</b></summary>

Adi JWT **JWS**-dir: **imzalanıb**, şifrələnməyib. Payload Base64-dür və hər kəs onu oxuya bilər; imza yalnız bütövlüyü və mənşəyi təsdiqləyir. **JWE** isə şifrələnmiş token-dir: məzmunu yalnız açarı olan oxuya bilər. Buna görə JWS-də məxfi məlumat saxlanılmır.
</details>

<details>
<summary><b>5. HS256 ilə RS256 arasında fərq nədir? Hansını seçmək lazımdır?</b></summary>

- **HS256** simmetrikdir: eyni gizli açar həm imzalayır, həm yoxlayır. Yoxlayan hər servis token **yarada** da bilir, açar isə hər yerə paylanmalıdır.
- **RS256** (və ES256) asimmetrikdir: private açar yalnız issuer-dədir, public açar isə (JWKS ilə) hər kəsə açıqdır.

Bir neçə servis token-i yoxlayırsa, RS256/ES256 seçilir. HS256 yalnız token-i verən və yoxlayan eyni servis olanda məqbuldur.
</details>

<details>
<summary><b>6. Resource server JWT-ni necə yoxlamalıdır?</b></summary>

1. İmzanı **gözlənilən alqoritm** və etibarlı açarla yoxlamaq (`kid` ilə JWKS-dən açarı seçmək).
2. `exp` və `nbf`-i yoxlamaq (kiçik clock skew ilə).
3. `iss`-in etibarlı issuer olduğunu yoxlamaq.
4. `aud`-un bu API olduğunu yoxlamaq.
5. Lazım olan claim-lərin (scope, rollar) olduğunu yoxlamaq.

Spring-də `JwtDecoder` və `JwtValidators`/`OAuth2TokenValidator`-lar bunu edir; `issuer-uri` verilməsi imza, `exp` və `iss` yoxlamasını avtomatik qurur.
</details>

<details>
<summary><b>7. "<code>alg: none</code>" və alqoritm qarışıqlığı hücumları nədir?</b></summary>

- **`alg: none`:** hücumçu header-də alqoritmi `none` yazır və imzanı silir; zəif kitabxana token-i imzasız qəbul edir.
- **Algorithm confusion:** RS256 üçün nəzərdə tutulmuş public açarı HS256 üçün "gizli açar" kimi istifadə etdirmək; public açar hamıya məlum olduğu üçün hücumçu etibarlı HS256 imzası yaradır.

Qorunma: alqoritmi token-in header-indən **götürməmək**; server tərəfdə gözlənilən alqoritmi və açar tipini sabitləmək.
</details>

<details>
<summary><b>8. JWT-də nə saxlamaq olar, nə olmaz?</b></summary>

- **Olar:** avtorizasiya üçün lazım olan kiçik, məxfi olmayan məlumat: istifadəçi id-si, rollar, scope-lar, tenant id-si.
- **Olmaz:** parol, şəxsi və maliyyə məlumatı (payload oxunaqlıdır), tez-tez dəyişən məlumat (token bitənə qədər köhnə qalır).

Token hər sorğuda göndərilir, ona görə kiçik olmalıdır: böyük token header limitlərini aşa və trafiki artıra bilər.
</details>

### Token həyat dövrü

<details>
<summary><b>9. Access token ilə refresh token arasında fərq nədir?</b></summary>

- **Access token** qısaömürlüdür (5-15 dəq) və hər API sorğusunda göndərilir; resource server onu bazasız yoxlayır.
- **Refresh token** uzunömürlüdür (günlər) və yalnız auth server-ə göndərilir, yeni access token almaq üçün. O, server-də saxlanılır və ləğv oluna bilər.

Bu bölgü təhlükəsizlik ilə rahatlığı balanslaşdırır: oğurlanmış access token tez bitir, istifadəçi isə tez-tez login olmur.
</details>

<details>
<summary><b>10. Refresh token rotation və reuse detection nədir?</b></summary>

- **Rotation:** hər refresh yeni refresh token qaytarır, köhnəsi etibarsız olur.
- **Reuse detection:** artıq istifadə olunmuş refresh token yenidən gəlirsə, deməli, onu kimsə kopyalayıb (oğru və ya əsl istifadəçi, bilmirik). Ona görə həmin login-in bütün token-ləri (family) ləğv olunur və istifadəçi yenidən login olur.

Bu, oğurlanmış refresh token-in ömrünü bir istifadəyə qədər qısaldır.
</details>

<details>
<summary><b>11. JWT ilə logout necə edilir? Token-i vaxtından əvvəl necə ləğv etmək olar?</b></summary>

JWT stateless-dir, onu "silmək" olmur. Variantlar:

- refresh token-i ləğv etmək və qısa access token ömrünə güvənmək (ən yayılmış yol);
- `jti` qara siyahısı (Redis, TTL = token-in qalan ömrü);
- istifadəçi üzrə "token version" (bazada; token-dəki versiya köhnədirsə, rədd edilir);
- opaque token + introspection.

Sonuncu üçü hər sorğuda lookup deməkdir, yəni stateless-in üstünlüyünün bir hissəsi itir.
</details>

<details>
<summary><b>12. Token-i brauzerdə harada saxlamaq lazımdır?</b></summary>

- **`localStorage`/`sessionStorage`:** XSS ilə oxunur, ona görə tövsiyə olunmur.
- **Yaddaşda (JS dəyişəni):** XSS-ə qarşı daha yaxşıdır, amma səhifə yenilənəndə itir.
- **`HttpOnly; Secure; SameSite` cookie:** JS görmür, amma brauzer onu avtomatik göndərir, ona görə CSRF-ə qarşı `SameSite` və/və ya CSRF token lazımdır.

Tövsiyə olunan: access token yaddaşda, refresh token `HttpOnly` cookie-də (dar `Path` ilə), və ya **BFF** pattern-i (token-lər ümumiyyətlə server-də qalır, brauzer yalnız sessiya cookie-si görür).
</details>

<details>
<summary><b>13. <code>kid</code>, JWKS və açar rotasiyası necə işləyir?</b></summary>

Auth server public açarlarını JWKS endpoint-ində (`/.well-known/jwks.json`) dərc edir; hər açarın `kid`-i var. Token-in header-indəki `kid` resource server-ə hansı açarla yoxlamaq lazım olduğunu deyir. Rotasiya belə gedir:

1. yeni açar JWKS-ə əlavə olunur;
2. yeni token-lər yeni açarla imzalanır;
3. köhnə açar köhnə token-lər bitənə qədər JWKS-də qalır, sonra silinir.

Resource server JWKS-i keşləyir və tanımadığı `kid` görəndə yenidən yükləyir.
</details>

<details>
<summary><b>14. Clock skew nədir?</b></summary>

Müxtəlif server-lərin saatları arasında kiçik fərqdir. Token-i verən server-in saatı yoxlayandan bir az irəlidədirsə, təzə token "hələ etibarlı deyil" (`nbf`, `iat`), və ya bitmə anında "artıq bitib" görünə bilər. Ona görə `exp`/`nbf` yoxlanarkən kiçik tolerantlıq (30-60 saniyə) verilir; Spring-də `JwtTimestampValidator(Duration)` ilə. Server-lərdə NTP isə məcburidir.
</details>

### OAuth 2.0 və OIDC

<details>
<summary><b>15. OAuth 2.0 ilə OpenID Connect arasında fərq nədir?</b></summary>

- **OAuth 2.0** avtorizasiya (delegasiya) protokoludur: tətbiqə istifadəçinin adından resursa **giriş** icazəsi verir (access token). İstifadəçinin kim olduğunu standart şəkildə demir.
- **OpenID Connect** OAuth 2.0 üzərində **autentifikasiya** qatıdır: ID token (JWT), `userinfo` endpoint-i, standart claim-lər (`sub`, `email`, `name`) və discovery (`/.well-known/openid-configuration`) əlavə edir.

"Google ilə daxil ol" OIDC-dir.
</details>

<details>
<summary><b>16. OAuth 2.0 grant type-ları hansılardır? Hansıları artıq tövsiyə olunmur?</b></summary>

- **Authorization Code + PKCE:** istifadəçi login-i üçün standartdır (veb, SPA, mobil).
- **Client Credentials:** servis → servis, istifadəçisiz.
- **Refresh Token:** yeni access token almaq üçün.
- **Device Code:** klaviaturası olmayan cihazlar (TV) üçün.

**Implicit** (token URL-də qayıdır) və **Resource Owner Password** (tətbiq istifadəçinin parolunu görür) OAuth 2.1 və müasir tövsiyələrdə **çıxarılıb**.
</details>

<details>
<summary><b>17. PKCE nədir və niyə lazımdır?</b></summary>

Proof Key for Code Exchange. Klient təsadüfi `code_verifier` yaradır və authorization sorğusunda onun hash-ini (`code_challenge`) göndərir; kodu token-ə dəyişəndə isə orijinal `code_verifier`-i göndərir. Server hash-in uyğun gəldiyini yoxlayır. Belə olanda yolda (məs. mobil cihazda başqa tətbiq tərəfindən) tutulmuş authorization code hücumçuya fayda vermir: onun `code_verifier`-i yoxdur. Mobil tətbiq və SPA kimi gizli açar saxlaya bilməyən klientlər üçün məcburidir, OAuth 2.1-də isə bütün klientlər üçün.
</details>

<details>
<summary><b>18. Opaque token ilə JWT arasında fərq nədir? Token introspection nədir?</b></summary>

- **Opaque token** mənasız təsadüfi sətirdir; resource server onu yoxlamaq üçün auth server-in **introspection** endpoint-inə (RFC 7662) sorğu göndərir. Dərhal ləğv etmək mümkündür, məlumat sızmır, amma hər sorğuda şəbəkə gedişi var (adətən keşlə yumşaldılır).
- **JWT** öz-özünü təsvir edir və yerli yoxlanılır: sürətlidir, amma dərhal ləğv etmək çətindir.
</details>

<details>
<summary><b>19. Servis → servis autentifikasiyası necə edilir?</b></summary>

- **Client Credentials** grant: servis öz `client_id`/`secret`-i (və ya private key JWT) ilə auth server-dən token alır və onu çağırışlarda göndərir; resource server scope-ları yoxlayır.
- **mTLS:** hər iki tərəf sertifikatla bir-birini təsdiqləyir; service mesh bunu avtomatik edə bilir.
- İstifadəçinin adından zəncirvari çağırış üçün **token exchange** (RFC 8693) və ya istifadəçi token-ini ötürmək.
</details>

### Spring Security

<details>
<summary><b>20. Spring Security daxildə necə işləyir?</b></summary>

Servlet sorğusu `DelegatingFilterProxy` → `FilterChainProxy` → uyğun `SecurityFilterChain`-dən keçir. Zəncirdə ardıcıl filtrlər var: CORS, CSRF, autentifikasiya filtrləri (`BearerTokenAuthenticationFilter`, `UsernamePasswordAuthenticationFilter`...), exception translation və avtorizasiya (`AuthorizationFilter`).

Autentifikasiya filtri `AuthenticationManager` → `AuthenticationProvider` ilə kimliyi təsdiqləyir və nəticəni `SecurityContextHolder`-a qoyur. Avtorizasiya həmin kontekstdəki `Authentication`-ın authority-lərinə baxır.
</details>

<details>
<summary><b>21. JWT ilə sorğu Spring-də necə emal olunur?</b></summary>

1. `BearerTokenAuthenticationFilter` `Authorization: Bearer` header-ini oxuyur.
2. `JwtAuthenticationProvider` token-i `JwtDecoder` ilə yoxlayır (imza, `exp`, `iss`...).
3. `JwtAuthenticationConverter` claim-ləri authority-lərə çevirir (default: `scope` → `SCOPE_*`).
4. `JwtAuthenticationToken` `SecurityContext`-ə yazılır.
5. `authorizeHttpRequests` və `@PreAuthorize` qaydaları yoxlanılır.

Xəta olanda `BearerTokenAuthenticationEntryPoint` 401, `BearerTokenAccessDeniedHandler` isə 403 qaytarır, hər ikisi `WWW-Authenticate` header-i ilə.
</details>

<details>
<summary><b>22. <code>hasRole</code> ilə <code>hasAuthority</code> arasında fərq nədir?</b></summary>

Authority sadə sətirdir (`SCOPE_orders:write`, `ROLE_ADMIN`). `hasAuthority("X")` dəqiq həmin sətri axtarır. `hasRole("ADMIN")` isə avtomatik `ROLE_` prefiksi əlavə edir və `ROLE_ADMIN`-i axtarır. Ona görə JWT-dəki `roles` claim-i authority-yə çevrilərkən `ROLE_` prefiksi əlavə olunmalıdır, scope-lar isə `hasAuthority("SCOPE_...")` ilə yoxlanılır.
</details>

<details>
<summary><b>23. Method security: <code>@PreAuthorize</code> nə vaxt, URL qaydaları nə vaxt?</b></summary>

URL qaydaları (`authorizeHttpRequests`) geniş, kobud qaydalar üçündür: "`/api/admin/**` yalnız admin", "hər şey autentifikasiya tələb edir". `@PreAuthorize` isə (`@EnableMethodSecurity` ilə) metod parametrlərinə və domen obyektlərinə baxan incə qaydalar üçündür: sahiblik (`#username == authentication.name`), şərti icazələr. Service qatındakı metodlarda da işləyir, ona görə məntiq başqa yoldan (məs. mesaj listener-dən) çağırılsa belə, qorunur.
</details>

<details>
<summary><b>24. Stateless API-də CSRF qorunmasını söndürmək təhlükəsizdirmi?</b></summary>

CSRF brauzerin **cookie**-ni başqa saytdan gələn sorğuya avtomatik əlavə etməsinə əsaslanır. Autentifikasiya `Authorization` header-i ilədirsə (brauzer onu özü əlavə etmir), CSRF mümkün deyil və qorunma söndürülə bilər. Amma autentifikasiya cookie ilədirsə (sessiya və ya refresh token cookie-si), qorunma lazımdır: `SameSite=Strict/Lax`, CSRF token və ya xüsusi header yoxlaması.
</details>

<details>
<summary><b>25. CORS nədir və təhlükəsizlik mexanizmidirmi?</b></summary>

CORS brauzerin same-origin policy-sini **yumşaldan** mexanizmdir: server hansı başqa origin-lərin (məs. `http://localhost:5173`-dəki SPA) onun API-sini JavaScript ilə çağırıb cavabı oxuya biləcəyini deyir. O, **server-i qorumur**: `curl` və ya başqa server CORS-a baxmır. CORS-un səhv konfiqurasiyası (`*` + credentials) isə brauzer istifadəçisinin məlumatını başqa saytlara açır. Spring-də `CorsConfigurationSource` bean-i ilə, filtr zəncirində `.cors()` ilə qurulur.
</details>

<details>
<summary><b>26. Parollar necə saxlanılmalıdır?</b></summary>

Heç vaxt açıq və ya sürətli hash (MD5, SHA-256) ilə yox. Qəsdən yavaş, salt-lı, adaptiv alqoritmlər istifadə olunur: **BCrypt**, **Argon2**, **scrypt**, PBKDF2. Spring-də `DelegatingPasswordEncoder` hash-in önünə alqoritmin id-sini yazır (`{bcrypt}...`), ona görə alqoritmi sonradan köhnə hash-ləri sındırmadan dəyişmək olur (istifadəçi növbəti login olanda yenidən hash-lənir).
</details>

### Hücumlar və təhlükəsizlik

<details>
<summary><b>27. IDOR (BOLA) nədir və necə qarşısı alınır?</b></summary>

Insecure Direct Object Reference / Broken Object Level Authorization: istifadəçi URL-dəki və ya body-dəki id-ni dəyişərək **başqasının** obyektinə çıxış əldə edir (`/api/orders/1002` əvəzinə `/api/orders/1003`). OWASP API Security Top 10-da birinci yerdədir. Qarşısını almaq üçün:

- hər obyekt əməliyyatında sahiblik və ya icazə yoxlamaq (`@PreAuthorize`, sorğuda `WHERE owner = :currentUser`);
- sahibi request-dən yox, token-dən götürmək;
- təxmin olunmayan id-lər (UUID), amma bu, yoxlamanı əvəz etmir.
</details>

<details>
<summary><b>28. Login endpoint-ini brute force-dan necə qorumaq olar?</b></summary>

- IP və istifadəçi adı üzrə rate limiting (Redis, API gateway);
- bir neçə uğursuz cəhddən sonra müvəqqəti bloklama və ya artan gecikmə;
- CAPTCHA;
- MFA;
- sızmış parolların yoxlanması (HaveIBeenPwned);
- uğursuz cəhdlər üçün monitorinq və alert;
- "istifadəçi yoxdur" və "parol səhvdir" üçün eyni cavab və eyni cavab müddəti (user enumeration-a qarşı).
</details>

<details>
<summary><b>29. XSS ilə CSRF arasında fərq nədir və token saxlama ilə əlaqəsi nədir?</b></summary>

- **XSS:** hücumçunun JavaScript-i **sizin** saytınızda icra olunur və JS-in əli çatan hər şeyi (`localStorage`, yaddaşdakı token) oğurlaya bilər.
- **CSRF:** **başqa** sayt brauzeri sizin saytınıza sorğu göndərməyə məcbur edir, brauzer isə cookie-ni avtomatik əlavə edir.

Token `localStorage`-dadırsa, XSS-ə qarşı həssasdır, CSRF-ə yox. `HttpOnly` cookie-dədirsə, XSS onu oxuya bilmir, amma CSRF-ə qarşı `SameSite` və ya CSRF token lazımdır.
</details>

<details>
<summary><b>30. Spring Security-ni necə test etmək olar?</b></summary>

- **MockMvc** ilə `@WithMockUser` (sadə istifadəçi) və `jwt()` post-processor-u (`.with(jwt().authorities(...))`): imzasız, yalnız avtorizasiya qaydaları yoxlanılır.
- **İnteqrasiya testləri** real HTTP və real token-lərlə bütün zənciri yoxlayır: vaxtı bitmiş token, dəyişdirilmiş imza, başqa açar, rotation.
- **Mənfi testlər** vacibdir: token-siz, başqa istifadəçinin resursu, çatışmayan scope. Onlar icazə verilməməli olanın həqiqətən rədd edildiyini yoxlayır.

Bu modulda hər iki növ var (20 test).
</details>

---

[← 22. Resilience4j](22-resilience.md) · [Mündəricat](README.md) · Növbəti: [24. Observability →](24-observability.md)
