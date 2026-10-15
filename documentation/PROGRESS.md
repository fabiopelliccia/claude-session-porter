# Avanzamento

Piano tecnico: [`PLAN.md`](PLAN.md). Questo file permette di interrompere e riprendere il lavoro: va
aggiornato alla fine di ogni pacchetto di lavoro (WP).

## Stato

| WP | Descrizione | Stato | Commit / note |
|---|---|---|---|
| 0 | Scheletro del repository | fatto | `09e6e2b`. Wrapper 9.7.1, `settings.gradle.kts` radice con solo `includeBuild("core")` (`:cli` lo aggiunge il WP6), CI `./gradlew -p core build`. |
| 1 | Spostamento del core e split dei bundle | fatto | `5071f52`. 79 test verdi (`./gradlew -p core build` e `./gradlew build`). Vedi note WP1 sotto. |
| 2 | IntelliJ sul core condiviso (submodule) | fatto | `ClaudeSessionRecover`, branch `feature/shared-core`, commit `1c09893` (submodule su `7086d36`). Verifica del 2026-10-15: `clean test buildPlugin verifyPlugin` verde (5 test, ripresi dalla cache di Gradle), `verifyPlugin` Compatible su IU-261.27258.48, IU-262.10968.63, IU-263.5153.40; `lib/` dello ZIP con core 2.0.0 e Gson 2.11.0, senza `kotlin-stdlib`. Senza merge né push. |
| 3 | Motore di anonimizzazione | fatto, correzioni della review da applicare | `e84caec`. 126 test verdi (79 + 47 nuovi). Review Opus del 2026-10-15: 18 rilievi in [`REVIEW-WP3.md`](REVIEW-WP3.md), più il n. 19 (conteggio residuo con utente `root`/`home`) emerso nel WP5. Vedi note WP3 sotto. |
| 4 | Export e manifest formato 2 | fatto | `abf6184` (fixture formato 1 rigenerata dal codice formato 1, `formatVersion: 1`, sessione `s-v1` di alice) e `eac8629`. Vedi note WP4/WP5 sotto. |
| 5 | Import del formato 2 | fatto | `a6605af`. 147 test, 0 falliti, 1 saltato (rilievo n. 19 della review WP3). Vedi note WP4/WP5 sotto. |
| 6 | CLI | da fare | |
| 7 | Plugin Claude Code e marketplace | da fare | |
| 8 | Strumenti di rilascio e documentazione | da fare | |
| 9 | IntelliJ 1.1.0, interfaccia per il formato 2 | da fare | |

Limite concordato: ci si ferma ai **test locali**. Nessun repository GitHub, push o rilascio senza il
proprietario.

## Decisioni

- 2026-10-14: plugin Claude Code + CLI Java 21 sul core Kotlin; export e import; progetto indipendente da
  IntelliJ; core condiviso via submodule (URL locale finché il repository GitHub non esiste);
  anonimizzazione completa, testo delle conversazioni incluso; stop ai test locali.

## Come riprendere

1. Leggere questo file e `PLAN.md`.
2. Build su Windows (fuori dal sandbox):
   `$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$PWD\build\uds"; .\gradlew.bat -p core build`
   (il `build` della radice esegue solo `buildEnvironment`: nessun task delega alla build inclusa; la CI usa `-p core build`).
3. Riprendere dal primo WP non "fatto".

### Ripresa (aggiornato il 2026-10-15)

WP2, WP4 e WP5 fatti; review del WP3 fatta. Ordine (sviluppo con Sonnet, review con Opus, come da CLAUDE.md):

1. **Correzioni della review del WP3** ([`REVIEW-WP3.md`](REVIEW-WP3.md), rilievi 1-19), con un test per ciascuna; togliere
   l'`@Ignore` del rilievo 19 in `CrossMachineTransferTest`.
2. In parallelo, **review del WP4/WP5** in sola lettura (`SessionExporter`, `SessionArchive`, `PathMapper`, `ImportDiagnostics`).
3. Prima del WP9: aggiornare il submodule di `ClaudeSessionRecover` all'ultimo commit di `develops`.
4. Poi WP6 (CLI), WP7 (plugin), WP8, WP9.

## Bug trovati nel codice esistente

| Data | Dove | Descrizione | Stato |
|---|---|---|---|
| 2026-10-14 | `ClaudeSessionRecover` `SessionArchive.readArchiveManifest` | un archivio prodotto da un altro strumento (export Copilot) faceva fallire l'import con `Parameter specified as non-null is null` | corretto in 0.0.5 |
| 2026-10-14 | `ClaudeSessionRecover` `ClaudeSessionsBundleTest` | i test sul bundle usavano la chiave UI `notification.action.showLog`, quindi non potevano vivere nel core | nel core puntano a `conflict.skip` (WP1) |

## Note WP1

- Chiavi nel bundle core (33 x 10 lingue): `conflict.skip|replace|duplicate`, `progress.exportingSession|exportCompleted|importingSession|importCompleted`,
  `export.warning.noTranscript|unreadable`, `import.warning.noTranscript`, `error.notAnArchive|notClaudeArchive|unreadableManifest|archiveTooNew`,
  `log.section.environment|context|sessions|diagnosis|comparison|summary`, `diagnosis.check.1..13`.
- `displayLanguage` ora è nel companion di `MessageBundle`: `MessageBundle.displayLanguage = {...}` (non più `ClaudeSessionsBundle.displayLanguage`).
  `ui/IdeDisplayLanguage.kt` va adattato nel WP2.
- Test rimosso dal core: `the plugin name is the same everywhere` (legge `META-INF/plugin.xml` e `gradle.properties`): diventa `PluginNamesTest` nel WP2.
  I test sul bundle usano `conflict.skip` al posto di `notification.action.showLog`.
- Nuovi: `LogFilesTest` (3), due test su `ProducerInfo` in `SessionArchiveTest`. `PluginNames` resta nel core (serve a `SessionArchive.PRODUCER`).
- `LogFiles.rotate(dir, prefix, keep)` cancella i file `<prefix>-*.log`; `ImportClaudeSessionsAction.rotateLogs` va sostituita nel WP2.
- Il WP2 deve tenere in `ClaudeSessionsBundle*` solo le chiavi UI (tutte tranne le 33 sopra) e creare `UiBundle`.

## Note WP3

File nuovi nel core (stesso package): `PathTokens.kt` (`PathKind`, `PathOs`, `PathStyle`, `PathToken`, `PathTokens`), `SourceIdentity.kt`,
`PathAnonymizer.kt`, `PathDeanonymizer.kt`, `PathInternals.kt` (interno: `AnchorPath`, `JsonLines`, `AnchorMatcher`).
Test: `PathAnonymizerTest` (26) e `PathDeanonymizerTest` (21). Il test JVM ha ora `maxHeapSize = "1g"` (il test di prestazione tiene in memoria 50 MB di
transcript; limite 20 s, `limitMillis` in `PathAnonymizerTest`, misurato sotto 1 s).

API per WP4/WP5:

- `SourceIdentity(home, claudeHome = null, roots = [], shortHomes = [], userName = null)`; `SourceIdentity.detect(claudeHome: Path, roots = [])` per la
  produzione (rileva le forme 8.3 da TEMP/TMP/`java.io.tmpdir`); `withRoots(list)`. `claudeHome` viene scartato se sta sotto la home.
- `PathAnonymizer(identity, tokenPrefix = "SP")`: `anonymizeText(text, stats)`, `anonymizeJsonLine(line, stats)`, `anonymizeJsonl(text, stats)`,
  `scanBytes(bytes): Int`, `countResidualUserName(text): Int`, `mayContainCandidate(text)`. `PathAnonymizer.Stats`: `replacedByKind`, `replacedByStyle`,
  `residualUserNameHits`, `parsedLines`, `rewrittenLines`, `unparsableLines`, `keyCollisions`, `totalReplacements`. Nessuno stato interno: le statistiche
  si passano come parametro (come in `SessionRedactor`). Va usato anche per nomi di entry dello ZIP e valori del manifest (`anonymizeText`).
- `PathDeanonymizer(root: String?, home, claudeHome, os = PathOs.of(home), tokenPrefix = "SP", sourceOs: PathOs? = null)`: `deanonymizeText`,
  `deanonymizeJsonLine`, `deanonymizeJsonl`, `render(kind, style): String?`; `PathDeanonymizer.Stats` (`renderedByKind`, `renderedByStyle`, `unresolvedTokens`, ...).
  `sourceOs` va preso dal manifest (`anonymization.sourceOs`).
- `PathTokens.render(kind, style, prefix)`, `parse`, `regex(prefix)`, `contains`, `count` (per il controllo n. 14), `choosePrefix(isUsed: (String) -> Boolean)`:
  chiama `isUsed("{{SP")` e, se serve, `isUsed("{{SPxxxx")` per il nonce; il prefisso va nel manifest (`anonymization.tokenPrefix`).

Scelte di progetto:

- Lo stile di un token è il livello di escape del *testo su cui si lavora*. `anonymizeJsonLine` decodifica: un percorso è `win`, un JSON dentro una stringa è `esc1`.
  Sul testo grezzo di una riga non valida (o di un file di testo) gli stessi percorsi appaiono un livello più in basso (`esc1`, `esc2`). Ogni punto di ingresso
  del deanonimizzatore è coerente con il suo omologo: non mescolare i due sulla stessa riga. Una riga non JSON all'export lo è anche all'import.
- Righe JSON: solo oggetti o array, con parsing rigoroso (`Strictness.STRICT`); tutto il resto passa dal testo grezzo. Riga cambiata: stesse impostazioni Gson di
  `SessionRedactor`; riga invariata: la stessa stringa (byte per byte). Chiavi che dopo l'anonimizzazione coinciderebbero: la seconda resta col nome originale e
  viene contata in `keyCollisions`.
- Stili oltre a quelli del piano: `esc3`, `shortfwd`, `short1`, `short2` (8.3 negli altri livelli) e `urlbs` (separatori `%5C`; `url` usa `/` o `%2F`).
  Bordo finale degli stili in chiaro: non seguono lettera, cifra, `_`, `~` né un punto seguito da una di queste, quindi `alice2`, `alice.bak` e `ALICE~1`
  non sono `alice` mentre il punto finale di una frase sì. Bordo di `enc`: qualsiasi carattere che non sia lettera, cifra o `_` (`-` incluso):
  `C--Users-alice-IdeaProjects-X` è ROOT, `C--Users-alice-Altro` è `{{SP:HOME:enc}}-Altro`.
- Gerarchia delle ancore: prima quella con più segmenti; a parità ROOT, poi CLAUDE_HOME, poi HOME. Radice uguale alla home: vince ROOT. Il pre-filtro cerca il nome
  (grezzo, codificato come cartella, percent-encoded, `\uXXXX`) delle sole ancore più esterne e lo stem 8.3: la foglia di una radice sotto la home (`X`, `src`)
  farebbe solo passare ogni riga.
- Deanonimizzatore: la coda dopo il token mantiene i separatori d'origine, salvo quando la destinazione è POSIX e lo stile è a backslash (`win`, `escN`, `short*`),
  oppure la sorgente è POSIX (`sourceOs`) e la destinazione Windows con token `fwd`: allora i separatori del tratto di percorso subito dopo il token (fino a spazio,
  apice o altro carattere non da percorso) diventano quelli della destinazione. Un `fwd` di una sorgente Windows resta con le barre dritte.

Limiti noti:

- I token non hanno un indice: con più radici in una sola identità tornano tutte alla stessa cartella di destinazione. Costruire **un anonimizzatore per sessione**,
  con la sola radice di quella sessione (altre radici fuori dalla home non vengono mascherate; sotto la home diventano `HOME`).
- Il nome 8.3 di una radice sotto la home diventa `{{SP:HOME:short}}\...`: la radice come tale non è più riconoscibile. Il fallback 8.3 usa
  `<drive>:\Users\<primi 6 caratteri A-Z0-9 del nome>~n`; un nome con caratteri non ASCII richiede la forma esplicita letta da TEMP.
- I percorsi UNC e quelli relativi non sono ancore. Non vengono riconosciuti nomi percent-encoded solo in parte (`%61lice`) né UTF-16BE; i binari sono solo contati
  (UTF-8 e UTF-16LE).
- La conversione dei separatori della coda è euristica: in testo libero un `\n` letterale dopo un percorso Windows verso POSIX diventa `/n`. Il nome utente fuori dai
  percorsi è solo contato (`residualUserNameHits`).
- Un file di testo JSON (per esempio `.meta.json`) va con `anonymizeText`: i suoi percorsi sono `esc1` e tornano `esc1`, quindi coerenti.

## Note WP4/WP5

- Export in `SessionExporter.kt` (interno), chiamato da `SessionArchive.export(..., identity: SourceIdentity = SourceIdentity.detect(home))`.
  Un `PathAnonymizer` per sessione, con la sola radice di quella sessione. Nomi delle entry dello ZIP anonimizzati; se due nomi
  coincidono, il secondo prende il suffisso `~2` (verificato solo su file system case-sensitive). `choosePrefix` controlla anche
  le stringhe e le chiavi decodificate (un `{{SP` letterale forza il nonce).
- Manifest v2: `sourceHome` assente; `projectPath`, `projectFolderName` e `summary` sono token grezzi (`{{SP:ROOT:win}}`), quindi
  le tabelle devono mostrare `projectName` (null per sessioni locali, archivi vecchi e radice uguale alla home). Il `summary` può
  contenere un token se il titolo cita un percorso. `ArchiveManifest.anonymization` = `AnonymizationInfo(scheme, tokenPrefix, kinds, sourceOs)`
  con `.os`; `kinds` elenca solo i tipi sostituiti; `isAnonymized` vero per il formato 2; `SessionArchive.ANONYMIZED_FORMAT = 2`.
- `ExportOutcome`: `replacedByKind`, `replacedByStyle`, `residualUserNameHits`, `residualEntries`, `binaryEntriesWithHits`,
  `partialLinesDropped`, `keyCollisions`. Riga finale parziale (anche negli aux `.jsonl`) scartata con `export.warning.partialLine` (10 lingue).
- Import: `import(..., userHome: String = user.home)`; il formato 2 richiede `targetProjectPath` (`error.targetRequired`, 10 lingue,
  niente scritto prima). `PathMapper(sourceRoots, target, extraFileAnchors)`, con il costruttore `(sourceRoot, target)` tenuto per il
  formato 1. Il deanonimizzatore passa anche sui nomi delle entry prima di costruire i percorsi. Controllo n. 14 (`CHECK_COUNT = 14`):
  nomi dei file e conteggi, mai il contenuto. `ImportDiagnostics.Target` ha il nuovo parametro `tokenPrefix`. Il log registra
  `manifest.anonymization` e i token resi per parte.
- Scostamenti dal piano: `residualUserNameHits == 3` (non 2), perché il branch `users/alice/fix` compare anche nel `gitBranch`
  del manifest (entry `[transcript, manifest.json]`); il vecchio test "tieni la cartella di alice" è diventato il test di target
  obbligatorio; la fixture usa una home POSIX per le radici POSIX. Test da 50 MB: 61 s per export + import, limite 240 s.
