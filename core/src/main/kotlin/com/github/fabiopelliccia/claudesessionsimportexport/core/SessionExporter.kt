package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * One run of [SessionArchive.export]: reads the sessions, redacts and anonymizes what it writes and
 * keeps the counts that end up in the [ExportOutcome].
 *
 * What a session contributes to the archive, and what is done to each part (in this order, see
 * [SessionRedactor] and [PathAnonymizer]):
 *
 * - the main transcript and every `.jsonl` file under its auxiliary folder (subagent runs): the
 *   unfinished last line of a live session is dropped, then the account identity is redacted and every
 *   path anchor of the session replaced, string values and object keys alike, an unchanged line
 *   staying byte for byte what it was;
 * - any other file of the auxiliary folder and of the file history: UTF-8 text (no NUL) goes through
 *   the same anchor replacement as plain text, anything else is copied verbatim and only scanned, a
 *   hit being reported by entry name;
 * - the ZIP entry names and the manifest values that hold a path (`projectPath`, `projectFolderName`,
 *   `gitBranch`), with `summary` taken again from the anonymized transcript.
 *
 * One [PathAnonymizer] per session, holding only that session's root: a token carries no index, so a
 * second root would render back to the same folder (see [SourceIdentity]).
 */
internal class SessionExporter(
    private val sessions: List<SessionInfo>,
    private val destination: Path,
    private val home: Path,
    private val progress: TransferProgress,
    private val producer: ProducerInfo,
    private val identity: SourceIdentity,
) {

    private val warnings = mutableListOf<String>()
    private val exported = mutableListOf<SessionInfo>()
    private var unreadable = 0
    private var partialLinesDropped = 0
    private val redaction = SessionRedactor.Stats()
    private val totals = Totals()
    private val usedNames = HashSet<String>()

    /** What was prepared for one entry: its bytes and what the pass over them counted. */
    private class Prepared(
        val bytes: ByteArray,
        val stats: PathAnonymizer.Stats,
        val partialLineDropped: Boolean = false,
        val binaryHits: Int = 0,
        /** The anonymized text of a transcript, kept to pick the session's title. */
        val text: String? = null,
    )

    /** The counts of every entry, merged; [residualEntries] and [binaryEntries] say where they were found. */
    private class Totals {
        val replacedByKind: MutableMap<String, Int> = sortedMapOf()
        val replacedByStyle: MutableMap<String, Int> = sortedMapOf()
        var residualUserNameHits = 0
        val residualEntries = linkedSetOf<String>()
        val binaryEntries = linkedSetOf<String>()
        var keyCollisions = 0

        fun add(entry: String, stats: PathAnonymizer.Stats) {
            stats.replacedByKind.forEach { (kind, count) -> replacedByKind.merge(kind, count, Int::plus) }
            stats.replacedByStyle.forEach { (style, count) -> replacedByStyle.merge(style, count, Int::plus) }
            if (stats.residualUserNameHits > 0) {
                residualUserNameHits += stats.residualUserNameHits
                residualEntries += entry
            }
            keyCollisions += stats.keyCollisions
        }
    }

    fun run(): ExportOutcome {
        val projectsDir = ClaudePaths.projectsDir(home)
        // One prefix for the whole archive, known before the first line is anonymized.
        val prefix = PathTokens.choosePrefix { needle -> sourcesContain(projectsDir, needle) }

        Files.createDirectories(destination.toAbsolutePath().parent)
        ZipOutputStream(Files.newOutputStream(destination).buffered()).use { zip ->
            sessions.forEachIndexed { index, session ->
                progress.report(
                    index.toDouble() / sessions.size,
                    ClaudeSessionsBundle.message("progress.exportingSession", session.displayName),
                )

                val projectDir = projectsDir.resolve(session.projectFolderName)
                val transcript = projectDir.resolve("${session.id}.jsonl")
                if (!transcript.isRegularFile()) {
                    warnings += ClaudeSessionsBundle.message("export.warning.noTranscript", session.displayName)
                    return@forEachIndexed
                }
                val anonymizer = PathAnonymizer(identity.withRoots(listOfNotNull(session.projectPath)), prefix)

                // Read, redact, anonymize and re-encode before the entry is opened: a file that fails
                // half way must not leave a truncated transcript in the archive.
                val main = try {
                    prepareTranscript(transcript, anonymizer)
                } catch (e: IOException) {
                    unreadable++
                    warnings += ClaudeSessionsBundle.message("export.warning.unreadable", session.displayName, transcript)
                    return@forEachIndexed
                }
                if (main.partialLineDropped) notePartialLine(session, TRANSCRIPT_NAME)
                writeEntry(zip, SessionArchive.transcriptEntry(session.id), main, anonymizer)

                val auxDir = projectDir.resolve(session.id)
                if (auxDir.isDirectory()) {
                    // A subagent run under here is a transcript of its own, in the same format, and
                    // gets the same passes; other files (tool results, ...) are text or binary.
                    copyDirectory(auxDir, SessionArchive.auxPrefix(session.id), zip, anonymizer, transcripts = true, session = session)
                }
                val fileHistoryDir = ClaudePaths.fileHistoryDir(home).resolve(session.id)
                if (fileHistoryDir.isDirectory()) {
                    // Raw backups of the user's own files, never a transcript: never redacted.
                    copyDirectory(fileHistoryDir, SessionArchive.fileHistoryPrefix(session.id), zip, anonymizer, transcripts = false, session = session)
                }
                exported += manifestEntry(session, anonymizer, main, auxDir.isDirectory(), fileHistoryDir.isDirectory())
            }

            // Written last, so it lists exactly the sessions that made it into the archive.
            val manifest = SessionArchive.ArchiveManifest(
                formatVersion = SessionArchive.FORMAT_VERSION,
                exportedAt = Instant.now().toString(),
                producer = SessionArchive.PRODUCER,
                sessions = exported,
                producerComponent = producer.component,
                producerVersion = producer.version,
                anonymization = SessionArchive.AnonymizationInfo(
                    tokenPrefix = prefix,
                    kinds = totals.replacedByKind.keys.toList(),
                    sourceOs = identity.os.name.lowercase(),
                ),
            )
            writeRaw(zip, SessionArchive.MANIFEST_ENTRY, SessionArchive.toJson(manifest).toByteArray(StandardCharsets.UTF_8))
            progress.report(1.0, ClaudeSessionsBundle.message("progress.exportCompleted"))
        }
        return ExportOutcome(
            exportedCount = exported.size,
            warnings = warnings,
            unreadableFiles = unreadable,
            redactedIdentityLines = redaction.redactedIdentityLines,
            redactedScratchpadPaths = redaction.redactedScratchpadPaths,
            replacedByKind = totals.replacedByKind,
            replacedByStyle = totals.replacedByStyle,
            residualUserNameHits = totals.residualUserNameHits,
            residualEntries = totals.residualEntries.toList(),
            binaryEntriesWithHits = totals.binaryEntries.toList(),
            partialLinesDropped = partialLinesDropped,
            keyCollisions = totals.keyCollisions,
        )
    }

    // ------------------------------------------------------------------------------- manifest --

    /**
     * The session as the manifest lists it: path-bearing values anonymized, `summary` picked again
     * from the anonymized transcript (so the import table shows what the archive really holds) and
     * `projectName` set to the leaf of the root, the one thing a token cannot tell.
     */
    private fun manifestEntry(
        session: SessionInfo,
        anonymizer: PathAnonymizer,
        main: Prepared,
        hasAux: Boolean,
        hasFileHistory: Boolean,
    ): SessionInfo {
        val stats = PathAnonymizer.Stats()
        val projectPath = session.projectPath?.let { anonymizer.anonymizeText(it, stats) }
        val folderName = anonymizer.anonymizeText(session.projectFolderName, stats)
        val gitBranch = session.gitBranch?.let { anonymizer.anonymizeText(it, stats) }
        val summary = main.text?.let { BufferedReader(StringReader(it)).use(SessionTitle::of) }
        // The summary comes from text that is already anonymized: only its bare user name is counted.
        summary?.let { stats.residualUserNameHits += anonymizer.countResidualUserName(it) }
        val projectName = projectNameOf(session.projectPath)
        projectName?.let { stats.residualUserNameHits += anonymizer.countResidualUserName(it) }
        totals.add(SessionArchive.MANIFEST_ENTRY, stats)
        return session.copy(
            projectPath = projectPath,
            projectFolderName = folderName,
            summary = summary,
            gitBranch = gitBranch,
            hasAuxData = hasAux,
            hasFileHistory = hasFileHistory,
            projectName = projectName,
        )
    }

    /** The leaf of [root]; `null` when there is none or when it is the user's own name (root == home). */
    private fun projectNameOf(root: String?): String? {
        val leaf = root?.let { AnchorPath.parse(ClaudePaths.normalizeProjectPath(it))?.leaf } ?: return null
        return leaf.takeUnless { it.equals(identity.userName, ignoreCase = true) }
    }

    // --------------------------------------------------------------------------------- entries --

    private fun prepareTranscript(file: Path, anonymizer: PathAnonymizer): Prepared {
        val stats = PathAnonymizer.Stats()
        val text = Files.readString(file, StandardCharsets.UTF_8)
        // A line still being written has no newline yet: the fragment is not a message, leave it out.
        val lastNewline = text.lastIndexOf('\n')
        val partial = text.substring(lastNewline + 1).isNotBlank()
        val whole = if (partial) text.substring(0, lastNewline + 1) else text
        val anonymized = anonymizer.anonymizeJsonl(SessionRedactor.redact(whole, redaction), stats)
        return Prepared(anonymized.toByteArray(StandardCharsets.UTF_8), stats, partialLineDropped = partial, text = anonymized)
    }

    /** A file that is not a transcript: UTF-8 text is anonymized, anything else is copied and scanned. */
    private fun prepareFile(file: Path, anonymizer: PathAnonymizer): Prepared {
        val stats = PathAnonymizer.Stats()
        val bytes = Files.readAllBytes(file)
        val text = decodeText(bytes) ?: return Prepared(bytes, stats, binaryHits = anonymizer.scanBytes(bytes))
        val anonymized = anonymizer.anonymizeText(text, stats)
        return Prepared(if (anonymized == text) bytes else anonymized.toByteArray(StandardCharsets.UTF_8), stats)
    }

    /** [bytes] as UTF-8 text, or `null` when they are not (invalid UTF-8 or a NUL: a binary file). */
    private fun decodeText(bytes: ByteArray): String? {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            return null
        }
        return text.takeIf { '\u0000' !in it }
    }

    /**
     * Writes every file of [dir] below [entryPrefix]; a file that cannot be read is reported and left
     * out instead of aborting the export. With [transcripts], `.jsonl` files are transcripts.
     */
    private fun copyDirectory(
        dir: Path,
        entryPrefix: String,
        zip: ZipOutputStream,
        anonymizer: PathAnonymizer,
        transcripts: Boolean,
        session: SessionInfo,
    ) {
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val relative = dir.relativize(file).toString().replace('\\', '/')
                val prepared = try {
                    if (transcripts && file.fileName.toString().endsWith(".jsonl", ignoreCase = true)) {
                        prepareTranscript(file, anonymizer)
                    } else {
                        prepareFile(file, anonymizer)
                    }
                } catch (e: IOException) {
                    unreadableFile(session, file)
                    return FileVisitResult.CONTINUE
                }
                if (prepared.partialLineDropped) notePartialLine(session, relative)
                writeEntry(zip, entryPrefix + relative, prepared, anonymizer)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                unreadableFile(session, file)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun unreadableFile(session: SessionInfo, file: Path) {
        unreadable++
        warnings += ClaudeSessionsBundle.message("export.warning.unreadable", session.displayName, file)
    }

    private fun notePartialLine(session: SessionInfo, entry: String) {
        partialLinesDropped++
        warnings += ClaudeSessionsBundle.message("export.warning.partialLine", session.displayName, entry)
    }

    /** Writes [prepared] under [name], which is anonymized itself (a file may be called `C--Users-alice-x.txt`). */
    private fun writeEntry(zip: ZipOutputStream, name: String, prepared: Prepared, anonymizer: PathAnonymizer) {
        val nameStats = PathAnonymizer.Stats()
        val entry = writeRaw(zip, anonymizer.anonymizeText(name, nameStats), prepared.bytes)
        totals.add(entry, nameStats)
        totals.add(entry, prepared.stats)
        if (prepared.binaryHits > 0) totals.binaryEntries += entry
    }

    /** Writes an entry and returns the name it got: two names that anonymize alike do not collide. */
    private fun writeRaw(zip: ZipOutputStream, name: String, content: ByteArray): String {
        val unique = uniqueName(name)
        zip.putNextEntry(ZipEntry(unique))
        zip.write(content)
        zip.closeEntry()
        return unique
    }

    private fun uniqueName(name: String): String {
        if (usedNames.add(name)) return name
        val slash = name.lastIndexOf('/')
        val dot = name.lastIndexOf('.')
        val cut = if (dot > slash + 1) dot else name.length
        var number = 2
        while (true) {
            val candidate = name.substring(0, cut) + "~$number" + name.substring(cut)
            if (usedNames.add(candidate)) return candidate
            number++
        }
    }

    // ------------------------------------------------------------------------------ token prefix --

    /** Whether any file of any session (or a value the manifest carries) holds [needle]. */
    private fun sourcesContain(projectsDir: Path, needle: String): Boolean {
        val bytes = needle.toByteArray(StandardCharsets.UTF_8)
        return sessions.any { session ->
            val values = listOfNotNull(session.summary, session.projectPath, session.projectFolderName, session.gitBranch)
            if (values.any { needle in it }) return@any true
            val projectDir = projectsDir.resolve(session.projectFolderName)
            val sources = listOf(projectDir.resolve("${session.id}.jsonl")) +
                filesUnder(projectDir.resolve(session.id)) + filesUnder(ClaudePaths.fileHistoryDir(home).resolve(session.id))
            sources.any { fileContains(it, bytes) }
        }
    }

    private fun filesUnder(dir: Path): List<Path> {
        if (!dir.isDirectory()) return emptyList()
        return runCatching { Files.walk(dir).use { paths -> paths.filter { it.isRegularFile() }.toList() } }.getOrDefault(emptyList())
    }

    /** Streams [file] looking for [needle]; a file that cannot be read holds nothing the import could misread. */
    private fun fileContains(file: Path, needle: ByteArray): Boolean {
        if (!file.isRegularFile()) return false
        return try {
            Files.newInputStream(file).use { input -> streamContains(input, needle) }
        } catch (e: IOException) {
            false
        }
    }

    private fun streamContains(input: InputStream, needle: ByteArray): Boolean {
        val buffer = ByteArray(1 shl 20)
        var kept = 0
        while (true) {
            val read = input.read(buffer, kept, buffer.size - kept)
            if (read < 0) return false
            val length = kept + read
            if (indexOf(buffer, length, needle) >= 0) return true
            // Keep the end of the block: a match may straddle two reads.
            kept = minOf(needle.size - 1, length)
            System.arraycopy(buffer, length - kept, buffer, 0, kept)
        }
    }

    private fun indexOf(buffer: ByteArray, length: Int, needle: ByteArray): Int {
        val last = length - needle.size
        var i = 0
        while (i <= last) {
            var j = 0
            while (j < needle.size && buffer[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private companion object {
        const val TRANSCRIPT_NAME = "transcript.jsonl"
    }
}
