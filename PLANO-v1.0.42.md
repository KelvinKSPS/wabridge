# Plano de execução — wabridge v1.0.42

Plano para um agente executar. Leia tudo antes de começar. Siga a ordem das fases: cada fase é um commit separado.

## Regras para quem executa

- Responder e comentar em pt-BR, igual ao código existente.
- Não fazer refactor fora do que está pedido. Não adicionar dependências.
- Não dizer que "funciona no celular" sem teste no aparelho. Ao final, listar o que precisa ser testado no celular.
- Um commit por fase, mensagem `wabridge: v1.0.42 — <fase>`, terminando com `Co-Authored-By: Claude <noreply@anthropic.com>`. Não fazer push.
- Validação obrigatória a cada fase:
  - Go: `cd embedded-core && go vet ./... && go test ./...`
  - Android: `cd android-bridge && ./gradlew testDebugUnitTest assembleDebug` (se o build não rodar nesta máquina, dizer isso explicitamente; o build oficial roda no GitHub Actions).

## Estado atual (lido do código)

| Área | Onde | Situação |
|---|---|---|
| Velocidades | `PlaybackSpeed.kt` | `menu` = 0.5–2.0, `MAX = 2.0`. O commit v1.0.41 mudou só o `coerceIn` do `VoiceSession` para 2.5, mas `PlaybackSpeed.clamp` e `Prefs.speed` ainda cortam em 2.0. Ou seja, 2.5x nunca é usado. |
| Lista duplicada | `MainActivity.kt:35` | `speedOptions` repete `PlaybackSpeed.menu`. `res/values/arrays.xml` tem `speed_values` sem uso. |
| Delay do áudio | `VoiceSession.answer()` | Não existe config. O commit v1.0.41 diz "Prefs.audioDelay" mas o código não tem. O atraso real é: espera do SCO (até `SCO_TIMEOUT_MS = 3000`) + `SCO_SETTLE_MS = 800` + download/prepare do `MediaPlayer` (só começa depois) + velocidade aplicada 200 ms depois do início. Por isso parece ~5 s. |
| Contatos/grupos | `whatsmeow_client.go: Contacts()` | Ordenados por nome. Não existe dado de "última conversa". O app mostra uma lista só, com cabeçalhos "contatos"/"grupos" (`MainActivity.showAllowedDialog`). |
| Bluetooth | `Prefs.btDevice` (1 endereço), `MainActivity.setupBluetooth`, `BridgeForegroundService.watchConnected()` | Só um aparelho. |
| Conta vinculada | daemon já tem `GET /auth/status` → `{connected, loggedIn, jid, phone}` | O app não chama essa rota. |
| Versão | `app/build.gradle.kts` | `versionCode = 39`, `versionName = "1.0.39"` (desatualizado em relação aos commits). |

---

## Fase 1 — Mais velocidades (Android)

**Arquivos:** `PlaybackSpeed.kt`, `VoiceSession.kt`, `MainActivity.kt`, `Prefs.kt`, `res/values/arrays.xml`, `PlaybackSpeedTest.kt`.

1. `PlaybackSpeed.kt`:
   - `options` (ciclo do botão na chamada) = `listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)`.
   - `menu` (velocidade padrão) = `listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)`.
   - `MAX = 3.0f`. Atualizar o comentário de `next()`.
   - `label()` já funciona para 1.75 ("1.75x") e 2.5 ("2.5x"); não mexer.
2. `VoiceSession.setSpeed()`: trocar `value.coerceIn(0.5f, 2.5f)` por `PlaybackSpeed.clamp(value)` e ajustar o KDoc ("0.5 a 3.0").
3. `MainActivity.kt`: apagar `speedOptions` e usar `PlaybackSpeed.menu` nos 3 lugares onde aparece.
4. `Prefs.speed`: corrigir comentário para "0.5 a 3.0".
5. `arrays.xml`: apagar o `string-array name="speed_values"` (sem uso; confirmar com `grep -rn speed_values android-bridge/app/src` antes).
6. `PlaybackSpeedTest.kt`: atualizar o teste do ciclo (passa por 1.25, 1.5, 1.75, 2, 2.5, 3, volta para 1), o teste de clamp (`clamp(5f) == 3f`) e adicionar `label(1.75f) == "1.75x"` e `label(3.0f) == "3x"`.

**Aceite:** testes passam; menu mostra 0.5x…3x; botão da chamada cicla até 3x.

---

## Fase 2 — Delay configurável para o áudio começar (Android)

Semântica: **"tempo entre atender e o áudio começar"**, opções 0 s, 0,5 s, 1 s, 2 s, 3 s, 5 s. Padrão 1 s.

Limite físico: o áudio só pode tocar com o canal SCO do relógio aberto. Com "0 s", o áudio começa assim que o SCO conectar (normalmente < 1 s; no máximo 3 s de timeout). Escrever isso no hint da UI.

**Arquivos:** `Prefs.kt`, `VoiceSession.kt`, `MainActivity.kt`, `res/layout/activity_main.xml`, `res/values/strings.xml`.

1. `Prefs.kt`:
   ```kotlin
   private const val KEY_AUDIO_DELAY = "audio_delay_ms"
   val AUDIO_DELAY_OPTIONS = listOf(0L, 500L, 1000L, 2000L, 3000L, 5000L)
   const val DEFAULT_AUDIO_DELAY_MS = 1000L
   fun audioDelayMs(ctx: Context): Long =
       ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
           .getLong(KEY_AUDIO_DELAY, DEFAULT_AUDIO_DELAY_MS).coerceIn(0L, 10_000L)
   fun setAudioDelayMs(ctx: Context, value: Long) { /* putLong com coerceIn(0, 10_000) */ }
   ```
2. `VoiceSession.answer()` — trocar o bloco `scope.launch { ... }` por:
   ```kotlin
   val answeredAt = SystemClock.elapsedRealtime()
   val delayMs = Prefs.audioDelayMs(ctx)
   preparePlayback()               // baixa e prepara o áudio JÁ, em paralelo à espera
   scope.launch {
       val ok = withTimeoutOrNull(SCO_TIMEOUT_MS) { scoConnected.await() } ?: false
       if (!ok) Log.w(TAG, "SCO não conectou a tempo; seguindo assim mesmo")
       val restante = delayMs - (SystemClock.elapsedRealtime() - answeredAt)
       if (restante > 0) delay(restante)
       Log.i(TAG, "áudio liberado ${SystemClock.elapsedRealtime() - answeredAt} ms após atender (config ${delayMs} ms)")
       playbackGate.complete(Unit)
   }
   ```
   - Remover `SCO_SETTLE_MS` (o delay do usuário substitui o "settle").
   - Dividir `startPlayback()` em `preparePlayback()`: cria o `MediaPlayer`, `setDataSource`, `prepareAsync()`. No `setOnPreparedListener`, **não** chamar `start()` direto: lançar `scope.launch { playbackGate.await(); startPrepared(mp) }`.
   - `private val playbackGate = CompletableDeferred<Unit>()`.
   - `startPrepared(mp)`: chama `state.onPlaybackStarted()` (mover a chamada para cá, para a máquina de estados só entrar em "tocando" quando tocar de fato), aplica a velocidade padrão **antes** do start: `val v = Prefs.speed(ctx); if (v != 1.0f) setSpeed(v)` e depois `if (!mp.isPlaying) mp.start()` (no Android, mudar `playbackParams` de um player preparado pode já iniciar a reprodução; por isso o `isPlaying`).
   - Apagar a linha `if (default != 1.0f) delay(200).let { setSpeed(default) }`.
   - Erro no prepare ou no `setDataSource`: manter o comportamento atual (`onPlaybackFinished()`), mas só depois do gate (`scope.launch { playbackGate.await(); onPlaybackFinished() }`), para não ir para a gravação antes do SCO.
   - Conferir `CallStateMachine`: se `onPlaybackStarted()` exigir fase anterior específica, garantir que nada entre `answer()` e `startPrepared()` mude a fase. Rodar `CallStateMachineTest`.
   - Se a sessão terminar (hangup) antes do gate, `hangup`/cleanup já faz `release()` do player; garantir que `startPrepared` saia cedo se `ended == true` ou `player !== mp`.
3. UI em `activity_main.xml`: logo abaixo do `speedSpinner`, um novo dropdown igual (mesmo estilo `ExposedDropdownMenu`) com `android:id="@+id/audioDelaySpinner"`, hint `@string/audio_delay_label`.
4. `strings.xml`:
   - `audio_delay_label` = "tempo até o áudio começar"
   - `audio_delay_hint` = "conta a partir do atender. Com 0 s o áudio espera só o relógio abrir o canal de voz."  (mostrar como `helperText` no `TextInputLayout`)
5. `MainActivity`: espelhar o setup do `speedSpinner`. Rótulos: `0 s`, `0,5 s`, `1 s`, `2 s`, `3 s`, `5 s` (função local `"%s s"` com vírgula). Ao escolher, `Prefs.setAudioDelayMs`. Valor salvo fora da lista → selecionar o padrão.

**Aceite:** build passa; `logcat -s VoiceSession` mostra "áudio liberado X ms após atender (config Y ms)" com X ≈ max(Y, tempo do SCO). **Precisa testar no celular** com 0 s, 1 s e 5 s.

---

## Fase 3 — Contatos e grupos: mais recentes primeiro + tabelas separadas

### 3a. Daemon (Go)

O whatsmeow não guarda data da última conversa. Vamos registrar nós mesmos, de duas fontes:
- `*events.HistorySync`: chega logo depois do pareamento com a lista de conversas e timestamps.
- `*events.Message`: toda mensagem nova (de qualquer tipo, enviada ou recebida, contato ou grupo).

**Limitação a documentar:** contas já pareadas não recebem o HistorySync de novo. Para elas a ordem vai se formando com as mensagens novas, ou o usuário desvincula e pareia de novo para receber o histórico.

**Novo arquivo `embedded-core/recent.go`:**
```go
// RecentStore guarda, por conversa, o unix (segundos) da última atividade.
// Chave: número (só dígitos) para contatos; JID completo ("...@g.us") para grupos.
type RecentStore struct {
    mu    sync.Mutex
    path  string
    m     map[string]int64
    dirty bool
}
func NewRecentStore(dir string) *RecentStore           // lê recent.json se existir; erro de leitura/JSON = mapa vazio
func (r *RecentStore) Touch(key string, ts int64)      // ignora key vazia/ts<=0; guarda o MAIOR ts; marca dirty
func (r *RecentStore) Get(key string) int64
func (r *RecentStore) SaveIfDirty() error              // grava em recent.json.tmp + os.Rename (atômico), perms 0o600
```
**Novo `embedded-core/recent_test.go`:** Touch mantém o maior; key vazia ignorada; Save + NewRecentStore relê os valores; JSON corrompido não quebra.

**`whatsmeow_client.go`:**
1. Campo `recent *RecentStore` no struct; criar em `NewWhatsmeow(dir, ...)` com `NewRecentStore(dir)`.
2. Goroutine com `time.NewTicker(15 * time.Second)` chamando `SaveIfDirty()` (iniciar em `Connect`, parar com `ctx.Done()`). Em `Close()`, chamar `SaveIfDirty()` antes de fechar o container.
3. Função auxiliar:
   ```go
   // recentKey devolve a chave da conversa (número para contato, JID para grupo) ou "".
   func (w *whatsmeowClient) recentKey(ctx context.Context, jid types.JID) string
   ```
   - `types.GroupServer` → `jid.ToNonAD().String()`
   - `types.HiddenUserServer` → resolver com `w.client.Store.LIDs.GetPNForLID(ctx, jid)`; se falhar, `""`
   - `types.DefaultUserServer` → `jid.User`
   - qualquer outro (broadcast/status, newsletter, bot) → `""`
4. `onEvent`:
   - Novo `case *events.HistorySync:` percorre `e.Data.GetConversations()`. Para cada uma: `jid, err := types.ParseJID(c.GetID())`; `ts := max(c.GetConversationTimestamp(), c.GetLastMsgTimestamp())`; `w.recent.Touch(w.recentKey(ctx, jid), int64(ts))`. Usar `context.WithTimeout(w.ctx, 30*time.Second)` para o lote todo. Logar `"wabridge: history sync %d conversas"`.
   - No `case *events.Message:` existente, **antes** de `audio := msg.GetAudioMessage()`, registrar a atividade: `w.recent.Touch(w.recentKey(ctx, e.Info.Chat), e.Info.Timestamp.Unix())` (ctx com timeout de 5 s, igual ao bloco de LID que já existe). Não mudar o resto do fluxo de áudio.
5. `ContactEntry` e `GroupEntry`: novo campo `LastActivity int64 \`json:"lastActivity"\`` (0 = sem dado).
6. `Contacts()`: preencher `LastActivity` (`w.recent.Get(numero)` / `w.recent.Get(g.JID.String())`) e trocar os dois `sort.Slice` por: `LastActivity` decrescente; empate → nome em minúsculas crescente. Extrair a regra numa função pura testável, ex. `sortRecentContacts([]ContactEntry)` e `sortRecentGroups([]GroupEntry)`, com teste em `whatsmeow_client_test.go`.
7. Contatos duplicados: se dois JIDs (PN e LID) resolverem para o mesmo número, manter um só (o de nome não vazio). Hoje pode duplicar; resolver com um `map[string]int` de índice por número.

**`server_test.go`:** `TestContacts` continua passando; adicionar assert de que o JSON tem `lastActivity`.

### 3b. App (Android)

**Arquivos:** `ContactItem.kt`, `PairingClient.kt`, `MainActivity.kt`, `strings.xml`.

1. `ContactItem`: novo campo `val lastActivity: Long = 0L` (segundos).
2. `PairingClient.parseItems`: ler `o.optLong("lastActivity")`. Manter a ordem que vem do daemon (não reordenar no app).
3. `showAllowedDialog` — trocar a lista única com cabeçalhos por **duas abas**:
   - Container vertical: `TabLayout` (Material, já está no projeto: `com.google.android.material.tabs.TabLayout`) com abas "contatos (N)" e "grupos (N)", depois o `EditText` de busca, depois **um** `ListView` multi-escolha.
   - Trocar de aba troca a fonte (`contatos` ou `grupos`) e reaplica a busca. A seleção (`selecionados`) é única e compartilhada entre as abas, como hoje.
   - Remover a lógica de cabeçalho (`item.value.isEmpty()`), que deixa de existir.
   - Texto da linha: `"${item.name} (${item.value})"` e, se `lastActivity > 0`, acrescentar `" · " + DateUtils.getRelativeTimeSpanString(item.lastActivity * 1000)` (`android.text.format.DateUtils`).
   - Para grupos, mostrar só o nome (o JID é ilegível); a busca continua casando por nome e valor.
   - Aba vazia: mostrar um item desabilitado/`setEmptyView` com "nenhum grupo" / "nenhum contato".
   - Dar altura fixa ao `ListView` (ex.: 400dp convertido para px) para o diálogo não pular de tamanho ao trocar de aba.
4. `strings.xml`: `allowed_tab_contacts_n` = "contatos (%d)", `allowed_tab_groups_n` = "grupos (%d)", `allowed_empty_tab` = "nada aqui".

**Aceite:** Go tests passam (incluindo os novos); no app, a lista abre na aba contatos com os mais recentes no topo e aba grupos separada; a seleção sobrevive à troca de aba e à busca. **Precisa testar no celular** (e conferir se a conta precisa ser pareada de novo para popular o histórico).

**Atenção (fora do escopo, só registrar no relatório final):** o daemon descarta toda nota de voz de grupo (`whatsmeow_client.go`, comentário "só conversa privada"), e o filtro `IsAllowed` compara só o número do remetente. Então marcar um grupo na lista hoje não faz nenhum áudio de grupo tocar. Não mudar isso nesta versão; avisar o usuário.

---

## Fase 4 — Bluetooth com vários aparelhos (Android)

Regra: com "só ligar quando conectado" ligado, toca se **qualquer um** dos aparelhos escolhidos estiver conectado. Lista vazia = qualquer um (comportamento atual).

**Arquivos:** `Prefs.kt`, `BridgeForegroundService.kt`, `MainActivity.kt`, `activity_main.xml`, `strings.xml`.

1. `Prefs.kt`:
   ```kotlin
   private const val KEY_BT_DEVICES = "bt_devices"
   fun btDevices(ctx: Context): Set<String>   // lê KEY_BT_DEVICES (csv); se ausente e KEY_BT_DEVICE existir, devolve setOf(antigo) (migração)
   fun setBtDevices(ctx: Context, value: Set<String>) // grava csv ordenado e remove KEY_BT_DEVICE
   ```
   Apagar `btDevice`/`setBtDevice` depois de trocar todos os usos (`grep -rn "btDevice\b\|setBtDevice" android-bridge/app/src` deve dar vazio no fim).
2. `BridgeForegroundService.watchConnected()`:
   ```kotlin
   if (!Prefs.btOnlyConnected(this)) return true
   val addrs = Prefs.btDevices(this)
   if (addrs.isEmpty()) return true
   return addrs.any { BluetoothHelper.isConnected(this, it) }
   ```
   Atualizar o log/status "relógio desconectado" para "nenhum aparelho escolhido conectado".
3. `activity_main.xml`, card Bluetooth: substituir o `TextInputLayout` + `btDeviceSpinner` por um `MaterialButton` tonal `@+id/btPickButton` (texto `@string/bt_pick`). Manter `btOnlyCheck` e `btState`.
4. `MainActivity.setupBluetooth()`:
   - Botão abre `AlertDialog.setMultiChoiceItems(nomes, marcados)` com os pareados de `BluetoothHelper.paired()`; OK grava `Prefs.setBtDevices`.
   - Sem pareados: Toast `bt_none`, não abrir diálogo.
   - Endereços salvos que não estão mais pareados: manter salvos, mas mostrar no estado como "(não pareado)".
5. `updateBtState()`: uma linha por aparelho escolhido: `"Relógio X — conectado agora"` / `"Fone Y — desconectado"` (usar `bt_connected`/`bt_disconnected`). Vazio → `bt_any` = "qualquer aparelho". Manter o ticker de 3 s.

**Aceite:** build passa; escolher 2 aparelhos, desligar um e confirmar que ainda toca; desligar os dois e confirmar que vai para a fila. **Precisa testar no celular.**

---

## Fase 5 — Mostrar se a conta está vinculada e qual número (Android)

Daemon já pronto: `GET /auth/status` → `{"connected":bool,"loggedIn":bool,"jid":"...","phone":"5519..."}`. Não mexer no Go.

**Arquivos:** `PairingClient.kt`, `MainActivity.kt`, `activity_main.xml`, `strings.xml`.

1. `PairingClient`:
   ```kotlin
   data class AuthStatus(val loggedIn: Boolean, val connected: Boolean, val phone: String)
   fun status(context: Context): AuthStatus?   // GET {engineUrl}/auth/status, timeouts 5s/5s, null em qualquer falha
   ```
2. `activity_main.xml`: no card de pareamento, logo abaixo do título `section_pairing`, um `TextView @+id/accountStatusText` (14sp, negrito).
3. `strings.xml`:
   - `account_linked` = "✓ conta vinculada: +%s"
   - `account_linked_offline` = "✓ conta vinculada: +%s (sem conexão agora)"
   - `account_not_linked` = "conta não vinculada — gere o código abaixo"
   - `account_unknown` = "status da conta indisponível (motor parado ou servidor externo)"
4. `MainActivity`:
   - `refreshAccountStatus()`: roda numa `Thread`, chama `PairingClient.status`, volta com `runOnUiThread`. Formatar o número como `+DDI DDD resto` quando começar com 55 e tiver 12–13 dígitos (ex.: `+55 19 98261-7622`); senão, `+` + dígitos.
   - Chamar em `onStart()`, depois que `DaemonManager.start` terminar no `onCreate`, e ao trocar o motor no `setupModeSpinner`.
   - Depois de gerar o código de pareamento com sucesso, repetir `refreshAccountStatus()` a cada 3 s por até 3 min, parando quando `loggedIn == true` ou quando a activity parar (`onStop` remove os callbacks). Usar `Handler(Looper.getMainLooper())`.
   - Quando `loggedIn == true`, preencher `phoneInput`/`countryCodeInput` só se estiverem vazios (não sobrescrever o que o usuário digitou). Não esconder o botão de parear.

**Aceite:** com conta pareada mostra o número; após desvincular no WhatsApp (aparelhos conectados) mostra "não vinculada"; modo externo mostra "indisponível" (o servidor Node não tem `/auth/status`). **Precisa testar no celular.**

---

## Fase 6 — Versão e fechamento

1. `android-bridge/app/build.gradle.kts`: `versionCode = 42`, `versionName = "1.0.42"`.
2. Rodar toda a validação (Go + Android).
3. Relatório final curto para o usuário, em pt-BR:
   - o que mudou por fase;
   - resultado dos testes (colar a saída se algo falhar);
   - lista "precisa testar no celular": delay 0/1/5 s, velocidades 2.5x e 3x no relógio, ordem dos contatos/grupos (e se precisou re-parear), 2 aparelhos BT, status da conta;
   - o aviso sobre grupos (fase 3) e sobre o v1.0.41 não ter aplicado 2.5x nem o delay.

## Arquivos tocados (resumo)

- Go: `embedded-core/recent.go` (novo), `recent_test.go` (novo), `whatsmeow_client.go`, `whatsmeow_client_test.go`, `server_test.go`
- Android: `PlaybackSpeed.kt`, `VoiceSession.kt`, `Prefs.kt`, `MainActivity.kt`, `BridgeForegroundService.kt`, `PairingClient.kt`, `ContactItem.kt`, `activity_main.xml`, `strings.xml`, `arrays.xml`, `PlaybackSpeedTest.kt`, `build.gradle.kts`
