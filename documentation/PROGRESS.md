# Avanzamento

Piano tecnico: [`PLAN.md`](PLAN.md). Questo file permette di interrompere e riprendere il lavoro: va
aggiornato alla fine di ogni pacchetto di lavoro (WP).

## Stato

| WP | Descrizione | Stato | Commit / note |
|---|---|---|---|
| 0 | Scheletro del repository | fatto | `09e6e2b`. Wrapper 9.7.1, `settings.gradle.kts` radice con solo `includeBuild("core")` (`:cli` lo aggiunge il WP6), CI `./gradlew -p core build`. |
| 1 | Spostamento del core e split dei bundle | fatto | `5071f52`. 79 test verdi (`./gradlew -p core build` e `./gradlew build`). Vedi note WP1 sotto. |
| 2 | IntelliJ sul core condiviso (submodule) | da fare | |
| 3 | Motore di anonimizzazione | da fare | |
| 4 | Export e manifest formato 2 | da fare | |
| 5 | Import del formato 2 | da fare | |
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
   `$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$PWD\build\uds"; .\gradlew.bat build`
3. Riprendere dal primo WP non "fatto".

## Bug trovati nel codice esistente

| Data | Dove | Descrizione | Stato |
|---|---|---|---|
| 2026-10-14 | `ClaudeSessionRecover` `SessionArchive.readArchiveManifest` | un archivio prodotto da un altro strumento (export Copilot) faceva fallire l'import con `Parameter specified as non-null is null` | corretto in 0.0.5 |
| 2026-10-14 | `ClaudeSessionRecover` `ClaudeSessionsBundleTest` | i test sul bundle usavano la chiave UI `notification.action.showLog`, quindi non potevano vivere nel core | nel core puntano a `conflict.skip` (WP1) |

## Note WP1

- Chiavi nel bundle core (33 x 10 lingue): `conflict.skip|replace|duplicate`, `progress.exportingSession|exportCompleted|importingSession|importCompleted`,
  `export.warning.noTranscript|unreadable`, `import.warning.noTranscript`, `error.notAnArchive|notClaudeArchive|unreadableManifest|archiveTooNew`,
  `log.section.environment|context|sessions|diagnosis|comparison|summary`, `diagnosis.check.1..13`.
- `displayLanguage` ora e` nel companion di `MessageBundle`: `MessageBundle.displayLanguage = {...}` (non piu` `ClaudeSessionsBundle.displayLanguage`).
  `ui/IdeDisplayLanguage.kt` va adattato nel WP2.
- Test rimosso dal core: `the plugin name is the same everywhere` (legge `META-INF/plugin.xml` e `gradle.properties`): diventa `PluginNamesTest` nel WP2.
  I test sul bundle usano `conflict.skip` al posto di `notification.action.showLog`.
- Nuovi: `LogFilesTest` (3), due test su `ProducerInfo` in `SessionArchiveTest`. `PluginNames` resta nel core (serve a `SessionArchive.PRODUCER`).
- `LogFiles.rotate(dir, prefix, keep)` cancella i file `<prefix>-*.log`; `ImportClaudeSessionsAction.rotateLogs` va sostituita nel WP2.
- Il WP2 deve tenere in `ClaudeSessionsBundle*` solo le chiavi UI (tutte tranne le 33 sopra) e creare `UiBundle`.
