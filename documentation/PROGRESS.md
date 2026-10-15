# Avanzamento

Piano tecnico: [`PLAN.md`](PLAN.md). Questo file permette di interrompere e riprendere il lavoro: va
aggiornato alla fine di ogni pacchetto di lavoro (WP).

## Stato

| WP | Descrizione | Stato | Commit / note |
|---|---|---|---|
| 0 | Scheletro del repository | fatto | `09e6e2b`. Wrapper 9.7.1, `settings.gradle.kts` radice con solo `includeBuild("core")` (`:cli` lo aggiunge il WP6), CI `./gradlew -p core build`. |
| 1 | Spostamento del core e split dei bundle | fatto | `5071f52`. 79 test verdi (`./gradlew -p core build` e `./gradlew build`). Vedi note WP1 sotto. |
| 2 | IntelliJ sul core condiviso (submodule) | abbandonato | Decisione del 2026-10-15: progetti distinti. Il branch `feature/shared-core` di `ClaudeSessionRecover` (commit `1c09893`, verificato verde) resta senza merge. IntelliJ si chiude con la 1.0.1 di sola sicurezza (`f346048`, preparata in locale). |
| 3 | Motore di anonimizzazione | fatto | `e84caec`; correzioni dei 19 rilievi di [`REVIEW-WP3.md`](REVIEW-WP3.md) in `6a1b8e3`..`58400a7` (8 commit). 182 test verdi, nessuno saltato. Vedi note WP3 sotto. |
| 4 | Export e manifest formato 2 | fatto | `abf6184` (fixture formato 1 rigenerata dal codice formato 1, `formatVersion: 1`, sessione `s-v1` di alice) e `eac8629`. Vedi note WP4/WP5 sotto. |
| 5 | Import del formato 2 | fatto | `a6605af`; correzioni dei 13 rilievi e delle lacune nei test di [`REVIEW-WP4-WP5.md`](REVIEW-WP4-WP5.md) in `16e9cd5`..`cc89608` (9 commit). 247 test verdi, nessuno saltato. Vedi note WP4/WP5 sotto. |
| 6 | CLI | fatto | `98f8e6a`..`19b590f`; correzioni dei 13 rilievi di [`REVIEW-WP6.md`](REVIEW-WP6.md) in `d59b8cd`..`5fe1b3b`. Test: core 254, CLI 123, tutti verdi; `./gradlew test` dalla radice ora esegue anche il core. Jar `session-porter.jar` 2.619.495 byte, SHA-256 `cbefc40a…2f31bbba59`, uguale in due build pulite e con checkout CRLF o LF. CI con job ubuntu e windows (mai eseguita: niente push). Vedi note WP6 sotto. |
| 7 | Plugin Claude Code e marketplace | fatto, prova interattiva del proprietario da fare | `5c720e1`, `e2647e1` (argomenti in `SESSION_PORTER_ARGS`). `claude plugin validate` superato (anche `--strict`); `plugin/tests/test-launchers.sh` 130/130 su Git Bash e pwsh. Jar `cbefc40a…` in `plugin/lib/`, escluso da git fino al WP8. Vedi note WP7 sotto. |
| 8 | Strumenti di rilascio e documentazione | da fare | |
| 9 | IntelliJ 1.1.0, interfaccia per il formato 2 | annullato | Decisione del 2026-10-15: il plugin IntelliJ non avrà nuove versioni oltre la 1.0.1. |

Limite concordato: ci si ferma ai **test locali**. Nessun repository GitHub, push o rilascio senza il
proprietario.

## Decisioni

- 2026-10-14: plugin Claude Code + CLI Java 21 sul core Kotlin; export e import; progetto indipendente da
  IntelliJ; core condiviso via submodule (URL locale finché il repository GitHub non esiste);
  anonimizzazione completa, testo delle conversazioni incluso; stop ai test locali.
- 2026-10-15: i due progetti restano **distinti**. Il plugin IntelliJ (`ClaudeSessionRecover`) è un progetto chiuso:
  ultima versione 1.0.1 (solo sicurezza), nessun submodule sul core, WP2 abbandonato e WP9 annullato. Il core non deve
  più restare compatibile con IntelliJ.

## Come riprendere

1. Leggere questo file e `PLAN.md`.
2. Build su Windows (fuori dal sandbox):
   `$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$PWD\build\uds"; .\gradlew.bat build`
   (dal WP6 il `build` della radice costruisce e testa core e CLI).
3. Riprendere dal primo WP non "fatto".

### Ripresa (aggiornato il 2026-10-15, pomeriggio)

Core, CLI (WP6, con le correzioni) e plugin (WP7) fatti; WP2 abbandonato, WP9 annullato. Prossimi passi:

1. **Prova interattiva del proprietario**: `claude --plugin-dir ./plugin`, poi la scheda Code di Claude Desktop (passi in
   "Note WP7"); correggere quello che emerge.
2. **WP8** (rilascio e documentazione).

## Bug trovati nel codice esistente

| Data | Dove | Descrizione | Stato |
|---|---|---|---|
| 2026-10-14 | `ClaudeSessionRecover` `SessionArchive.readArchiveManifest` | un archivio prodotto da un altro strumento (export Copilot) faceva fallire l'import con `Parameter specified as non-null is null` | corretto in 0.0.5 |
| 2026-10-15 | core `SessionArchive.readArchiveManifest` / import (anche IntelliJ 1.0.0) | l'id di sessione e `projectFolderName` del manifest non venivano controllati: un archivio costruito apposta (id `../../../../x`) scriveva file fuori dalla Claude home | IntelliJ: corretto in 1.0.1 (`f346048`, in locale); core: corretto in `16e9cd5` (`error.unsafeArchive`, `ArchiveSafetyTest`) |
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

### Correzioni della review WP3 (2026-10-15)

- Nuovi file interni: `AnchorMatcher.kt`, `PathNamePatterns.kt`, `PathNeedles.kt`. Nessuna firma cambiata.
- `PathStyle` ha tre nuovi valori `REL`, `REL1`, `REL2` (token `rel`, `rel1`, `rel2`: home senza lettera di unità, per esempio
  `HOMEPATH=\Users\alice`). Chi elenca o fa `when` sugli stili deve conoscerli.
- `SourceIdentity`: nuove proprietà `shortStems` e `shortExtension` (8.3 di nomi con il punto: `alice.smith` → `ALICE~1.SMI`; si
  accettano sia la radice secondo la regola Windows sia la vecchia `ALICES`). `claudeHome` resta anche quando sta sotto una radice.
- Un'ancora di due o più cartelle non ha più controllo sul carattere che la precede (la privacy vince sull'ordine):
  `/data/home/alice/x` diventa `/data{{SP:HOME:fwd}}/x`. Un'ancora di una sola cartella tiene i suoi bordi, allentati per flag `-I`,
  colori ANSI e `\n`.
- Le forme 8.3 di Git Bash, WSL, Cygwin, codificata e a otto backslash usano lo stile della forma lunga (`msys`, `wsl`, `cyg`, `enc`, `esc3`).
- Chiavi in collisione: la seconda prende `~2`, `~3`, ... (`Stats.keyCollisions` ora conta le rinominate); all'import la chiave resta
  numerata e quindi non corrisponde a un file reale.
- `countResidualUserName` ignora i token dell'anonimizzatore; il controllo residuo guarda anche le parti del nome separate dal
  punto (4+ caratteri).
- `fwd` da sorgente Windows verso POSIX: la coda con backslash si converte solo se `sourceOs == WINDOWS`.
- Prestazioni: la regex parte solo dove un'alternativa può iniziare. Test da 50 MB export + import: da circa 53 s a 6,2 s.
- Limiti rimasti: nome utente nudo fuori dai percorsi solo contato; UTF-16BE e lettere ASCII percent-encoded (`%61lice`) non
  riconosciuti; il fallback 8.3 richiede lettere ASCII e il fallback codificato `<drive>--Users-STEM-<n>` può scambiare una
  cartella reale `alice\1` per la home 8.3; l'host di una share amministrativa (`\\ALICE-PC\c$`) resta, solo `\Users\alice`
  diventa token; un nome di cartella codificato fatto di soli trattini (home `/Алиса`) non viene cercato; per nomi come `Zoë` gli
  aghi sono deboli e quasi ogni riga passa dal parser (costo ormai piccolo).

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

### Correzioni della review WP4/WP5 (2026-10-15)

- Sicurezza: `readArchiveManifest` e `import` rifiutano id e nomi di cartella non `[A-Za-z0-9._-]+` (o `.`/`..`) con
  `IOException` e `error.unsafeArchive`, prima di scrivere; secondo controllo sui percorsi dopo `normalize()`. Prefisso dei token
  non valido o blocco `anonymization` mancante: `error.unreadableManifest`.
- Import a stadi: ogni sessione viene preparata in `<home>/.session-porter-staging-*`; con REPLACE la copia vecchia viene
  messa da parte e ripristinata se lo spostamento fallisce. Una sessione che fallisce non lascia nulla (anche per `OutOfMemoryError`).
- Identità: `SourceIdentity.detect(..., accountName)` prende come home principale quella che contiene la cartella `.claude`
  esportata e aggiunge `user.home` come ancora HOME; legge le share WSL (`\\wsl.localhost\...` come `/home/alice`). Nuovi
  parametri `otherHomes`, `otherNames`; membri `allHomes`, `allUserNames`, `isUserName`. `claudeHome` non viene mai scartato;
  `sourceOs` viene dai percorsi delle sessioni esportate.
- Export: UTF-8 con NUL e UTF-16LE/BE con BOM anonimizzati come testo; email e nome git trovati dal redactor sostituiti in ogni
  entry di testo e nel manifest (`ExportOutcome.identityValuesScrubbed`). Riga finale: taglio sui byte dopo l'ultimo `0x0A`; una
  riga JSON completa senza `\n` viene tenuta; una sessione senza righe complete viene saltata con `export.warning.emptySession`.
  ZIP scritto in `.session-porter-*.zip.tmp` accanto alla destinazione e spostato in modo atomico.
- File oltre 32 MB (`LARGE_FILE_BYTES`): copiati così come sono e scansionati a blocchi (1 MiB, sovrapposizione 4 KB), segnalati
  come i binari. `ExportOutcome.hasPrivacyFindings` = `residualUserNameHits > 0` o `binaryEntriesWithHits` non vuoto (che ora
  include anche il nome utente nudo e i file oltre 32 MB): `--strict-privacy` deve fallire su questo.
- Import: righe accettate solo dal JSON permissivo trattate come testo grezzo da entrambi i lati; CLAUDE_HOME sempre ancora; con
  DUPLICATE l'id di origine nei percorsi `projects/<cartella>/<id>/` e `file-history/<id>/` diventa quello nuovo; nella tabella
  il riepilogo mostra il nome del progetto (o `~`) al posto dei token.
- Motore: nuovi `PathStyle.REL_URL_BACKSLASH` e `REL_URL` (`Users%5Calice`, `users%2Falice`, trovati dal nuovo test di privacy
  indipendente).
- Limiti rimasti: email e nome git non sostituiti nei binari e nei file oltre 32 MB (solo ancore e nome utente vengono contati);
  transcript e aux `.jsonl` elaborati in memoria (troppo grandi per l'heap: "illeggibile" all'export, sessione fallita
  all'import); la home proprietaria della Claude home si riconosce solo se la cartella si chiama `.claude` e il genitore ha
  almeno due livelli; `/Users/alice` senza unità non viene letto come home Windows; il rollback dello spostamento è best effort;
  la riscrittura dell'id con DUPLICATE tocca solo i percorsi `projects/<cartella>/<id>/` e `file-history/<id>/`.

## Note WP6

- Modulo `cli/` (package `com.github.fabiopelliccia.sessionporter.cli`), shadow 9.6.1, `session-porter.jar` in `cli/build/libs/`;
  task `dist*`/`shadowDist*` disattivati (i launcher stanno nel plugin). `.gitattributes` forza LF su `*.properties`, `LICENSE` e
  licenze, altrimenti i byte del jar dipenderebbero dal checkout. Bundle CLI in 10 lingue; la sintassi dell'help è solo in inglese.
- Comandi: `list [--project] [--since ISO|7d|12h|2w] [--limit]`, `inspect <zip>`, `export (--ids | --ids-file f|- | --all |
  --project) --out <file.zip|cartella> [--force] [--strict-privacy] [--log-dir]`, `import <zip> --target T [--ids] [--policy
  skip|replace|duplicate] [--no-shift] [--keep-original-folder] [--log-dir]`, `doctor`, `version`, `help`.
- Codici di uscita: 0 ok; 2 uso (`error.code` tra cui `unknownSessions`, `noSessions`, `targetRequired`, `outputExists` è 4);
  3 archivio non valido o non sicuro (`invalidArchive`, `unsafeArchive`); 4 I/O o ambiente; 5 parziale o controlli KO
  (`partialExport`, `partialImport`); 6 `--strict-privacy` (`privacyFindings`); 10 interno. 20/21 li produce il launcher (WP7).
- Scostamenti da PLAN §2: con uscita 5 o 6 `data` resta valorizzato insieme a `ok:false` ed `error` (per 6 `archive` è null);
  l'export scrive un file temporaneo e lo sposta solo se accettato; `--out` deve essere `.zip`, una cartella esistente o finire con
  `/` o `\`; `--log-dir` anche per `export`; per il formato 1 senza `--target` ogni sessione tiene la sua cartella
  (`--keep-original-folder` lo dichiara, rifiutato col formato 2 e insieme a `--target`); id sconosciuti o zero sessioni → 2;
  cartella di destinazione inesistente → avviso e uscita 5 (controllo 8 KO); percorsi relativi risolti dalla cartella corrente;
  campi JSON in più: `list.data.totalMatching`, `inspect.data.alreadyPresentIds` e `presentLocally` per sessione,
  `export.data.requestedCount/sizeBytes/privacyFindings/logFile`, `import.data.policy`, dati di `doctor`.
- Per il WP7: sempre `--json`; prima il codice di uscita, poi `error.code`; stdout in JSON è una sola riga, stderr vuoto senza
  `--verbose`; `--ids-file -` legge stdin UTF-8 (separatori virgola, spazio, a capo; `#` commento); per il conflitto usare
  `inspect.data.alreadyPresentIds`; in Git Bash `--target "$PWD"` va bene (la CLI converte `/c/...`); per riprendere usare
  `imported[].resumeCommand` dalla cartella `data.target`; la CLI scrive UTF-8 a prescindere dai flag della JVM.

## Note WP7

- Skill con namespace: `/session-porter:export-sessions` e `/session-porter:import-sessions` (`disable-model-invocation: true`).
  `allowed-tools` limitato al launcher, scritto sia tra virgolette sia senza (Bash e PowerShell): da verificare nella prova se
  il launcher parte senza richiesta di permesso.
- Correzioni ai "plugin facts" di PLAN §3 (docs code.claude.com): nella scheda Code di Claude Desktop `+ > Plugins > Add plugin`
  sfoglia solo i marketplace già configurati; il marketplace si aggiunge da terminale (`claude plugin marketplace add <percorso>`),
  che condivide le impostazioni con l'app; i plugin non funzionano nelle sessioni cloud e WSL dell'app. Un marketplace locale con
  sorgente relativa viene letto sul posto (il jar escluso da git funziona per la prova locale; da GitHub mancherebbe fino al WP8).
- Scelte nello skill rispetto al piano: id passati con `--ids a,b,c` (oltre 200 con here-document) invece di `--ids-file -`, per
  non avere una pipe da autorizzare; cartella corrente dal contesto dell'ambiente; Downloads come `"$HOME/Downloads/"`; percorsi
  con barre dritte; sessione in corso riconosciuta con `${CLAUDE_SESSION_ID}` (sostituzione da verificare); con più progetti in un
  archivio chiede se usare una sola cartella o importare progetto per progetto (la CLI accetta un solo `--target`).
- Launcher: codice extra 4 `JAR_NOT_FOUND`; senza `--json` stampa testo su stderr. Avvio 2-3 s (soprattutto `java -XshowSettings`).
- Da verificare solo con la prova interattiva: gli skill stessi, la sostituzione di `${CLAUDE_PLUGIN_ROOT}` e `${CLAUDE_SESSION_ID}`,
  le regole di permesso, se le sessioni importate compaiono nella barra laterale dell'app o solo con `/resume` (rischio 4).

### Correzioni della review WP6 (2026-10-15)

- Argomenti Unicode su Windows: il launcher mette tutti gli argomenti, uno per riga, nella variabile `SESSION_PORTER_ARGS` e
  avvia java senza argv (java.exe riceve l'argv nella code page ANSI e riespande `*`/`?`). La CLI legge la variabile solo se l'argv
  è vuoto; un argomento con un a capo o oltre 30000 caratteri torna all'argv normale.
- Nuovi codici: `interrupted` (uscita **130**, hook di arresto che cancella i file temporanei; non copre TerminateProcess e
  lascia la cartella di staging dell'import), `outOfMemory` (10), `archiveTooNew` (3), `idsFromConsole` (2). `noSessions` anche
  per `import --ids ""`; `invalidPath` per ogni percorso vuoto; `invalidSince` per `--since` fuori scala; `ioError` anche per
  `UncheckedIOException`; `command` valorizzato negli errori d'uso dopo il comando; archivi classificati per tipo d'eccezione
  (core `UnusableArchiveException`).
- L'archivio in preparazione sta nella cartella temporanea di sistema e va nella destinazione solo se accettato (copia ed
  eliminazione se su un altro volume); le cartelle create per `--out` vengono tolte se l'export fallisce o è rifiutato.
- `--project` confronta il percorso registrato (il nome della cartella Claude solo se manca); `--project` e `--target` esistenti
  passano da `toRealPath` (8.3 e maiuscole come sul disco). Warning e avanzamento citano le sessioni per id (core
  `SessionLabeler`, default `TITLE`, la CLI usa `ID`).
- `LogFiles.rotate` tocca solo `<prefix>-yyyyMMdd-HHmmss[-n].log`; due run nello stesso secondo non si sovrascrivono.
- Jar: risorse `*.properties`/`*.txt` normalizzate a LF nella build, `.gitattributes` con `*.txt text eol=lf`.
