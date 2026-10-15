package com.github.fabiopelliccia.claudesessionsimportexport.core

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
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
 *   unfinished last line of a live session is dropped, then the account identity is redacted (the lines
 *   that carry it, [SessionRedactor]; the email and git name wherever else they are, [AccountIdentityScrubber])
 *   and every path anchor of the session replaced, string values and object keys alike, an unchanged line
 *   staying byte for byte what it was;
 * - any other file of the auxiliary folder and of the file history: text (UTF-8, NULs included, or UTF-16 with
 *   a byte order mark) goes through the same two replacements as plain text and is written back in its own
 *   encoding, anything else is copied verbatim and only scanned, a hit being reported by entry name. A file
 *   above [LARGE_FILE_BYTES] is never read whole: it is scanned in chunks and copied as it is, and reported
 *   like a binary one;
 * - the ZIP entry names and the manifest values that hold a path (`projectPath`, `projectFolderName`,
 *   `gitBranch`), with `summary` taken again from the anonymized transcript.
 *
 * One [PathAnonymizer] per session, holding only that session's root: a token carries no index, so a
 * second root would render back to the same folder (see [SourceIdentity]).
 *
 * The archive is written to a temporary file next to [destination] and moved over it at the end, so a
 * destination that already exists is never left half written; a file that cannot be read (an error as
 * well as an exception) is reported and left out, it does not stop the export.
 */
internal class SessionExporter(
    private val sessions: List<SessionInfo>,
    private val destination: Path,
    private val home: Path,
    private val progress: TransferProgress,
    private val producer: ProducerInfo,
    private val identity: SourceIdentity,
    /** Files above this size are not read whole; a parameter so that a test does not need a file of 32 MB. */
    private val largeFileBytes: Long = LARGE_FILE_BYTES,
    /** How a file is read into memory; a parameter so that a test can make it fail. */
    private val readBytes: (Path) -> ByteArray = Files::readAllBytes,
) {

    private val warnings = mutableListOf<String>()
    private val exported = mutableListOf<SessionInfo>()
    private var unreadable = 0
    private var partialLinesDropped = 0
    private val redaction = SessionRedactor.Stats()

    /** The account email and git user name found in the sessions, removed from every text; known before the first entry is written. */
    private var scrubber = AccountIdentityScrubber.NONE
    private val scrubStats = AccountIdentityScrubber.Stats()
    private val totals = Totals()
    private val usedNames = HashSet<String>()

    /** The operating systems the project paths of the exported sessions are written for. */
    private val seenOs = linkedSetOf<PathOs>()

    /**
     * What was prepared for one entry: its bytes (or, for a file too large to read whole, the file they
     * are copied from) and what the pass over them counted.
     */
    private class Prepared(
        val bytes: ByteArray?,
        val stats: PathAnonymizer.Stats,
        val partialLineDropped: Boolean = false,
        val binaryHits: Int = 0,
        /** The anonymized text of a transcript, kept to pick the session's title. */
        val text: String? = null,
        /** Set when [bytes] is `null`: the file the entry is streamed from. */
        val copiedFrom: Path? = null,
        /** A transcript that holds no complete line at all. */
        val isEmpty: Boolean = false,
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
        val parent = destination.toAbsolutePath().parent
        Files.createDirectories(parent)
        // Written next to the destination (so the move below never crosses a volume) and moved over it
        // at the end: whatever goes wrong before that, the destination is what it was.
        val temporary = Files.createTempFile(parent, TEMPORARY_PREFIX, TEMPORARY_SUFFIX)
        try {
            ZipOutputStream(Files.newOutputStream(temporary).buffered()).use { zip -> writeArchive(zip) }
            moveOverDestination(temporary)
        } finally {
            Files.deleteIfExists(temporary)
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
            identityValuesScrubbed = scrubStats.replacements,
        )
    }

    private fun moveOverDestination(temporary: Path) {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeArchive(zip: ZipOutputStream) {
        val projectsDir = ClaudePaths.projectsDir(home)
        // One prefix for the whole archive, known before the first line is anonymized.
        val prefix = PathTokens.choosePrefix { needle -> sourcesContain(projectsDir, needle) }
        scrubber = AccountIdentityScrubber.collect(transcriptsOfAllSessions(projectsDir))

        sessions.forEachIndexed { index, session ->
            progress.report(
                index.toDouble() / sessions.size,
                ClaudeSessionsBundle.message("progress.exportingSession", session.displayName),
            )
            exportSession(zip, session, projectsDir, prefix)
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
                sourceOs = sourceOs().name.lowercase(),
            ),
        )
        writeRaw(zip, SessionArchive.MANIFEST_ENTRY, SessionArchive.toJson(manifest).toByteArray(StandardCharsets.UTF_8))
        progress.report(1.0, ClaudeSessionsBundle.message("progress.exportCompleted"))
    }

    private fun exportSession(zip: ZipOutputStream, session: SessionInfo, projectsDir: Path, prefix: String) {
        val projectDir = projectsDir.resolve(session.projectFolderName)
        val transcript = projectDir.resolve("${session.id}.jsonl")
        if (!transcript.isRegularFile()) {
            warnings += ClaudeSessionsBundle.message("export.warning.noTranscript", session.displayName)
            return
        }
        val anonymizer = PathAnonymizer(identity.withRoots(listOfNotNull(session.projectPath)), prefix)

        // Read, redact, anonymize and re-encode before the entry is opened: a file that fails
        // half way must not leave a truncated transcript in the archive.
        val main = unlessUnreadable({ unreadableFile(session, transcript) }) { prepareTranscript(transcript, anonymizer) } ?: return
        if (main.isEmpty) {
            // Nothing but a fragment of a line (or nothing at all): a session that would import as an empty one.
            warnings += ClaudeSessionsBundle.message("export.warning.emptySession", session.displayName)
            return
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
        session.projectPath?.let { seenOs += PathOs.of(ClaudePaths.normalizeProjectPath(it)) }
        exported += manifestEntry(session, anonymizer, main, auxDir.isDirectory(), fileHistoryDir.isDirectory())
    }

    /**
     * Runs [block] and returns its result; when reading fails - with an exception, or with an error that is
     * about this file only (a file too large for the heap, a document nested too deep to parse) - calls [onFailure]
     * and returns `null`. Other errors are not about the file and go on.
     */
    private fun <T> unlessUnreadable(onFailure: () -> Unit, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            onFailure()
            null
        } catch (e: OutOfMemoryError) {
            onFailure()
            null
        } catch (e: StackOverflowError) {
            onFailure()
            null
        }

    /**
     * The operating system the paths of the archive are written for: the one of the project paths when every exported
     * session agrees, otherwise the one of the identity (a Windows account exporting the Claude home of a WSL
     * session has POSIX paths, which the home it runs from says nothing about).
     */
    private fun sourceOs(): PathOs = seenOs.singleOrNull() ?: identity.os

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
        val gitBranch = session.gitBranch?.let { anonymizer.anonymizeText(scrubber.scrubText(it, scrubStats), stats) }
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

    /** The leaf of [root]; `null` when there is none or when it is a name of the user (root == home). */
    private fun projectNameOf(root: String?): String? {
        val leaf = root?.let { AnchorPath.parse(ClaudePaths.normalizeProjectPath(it))?.leaf } ?: return null
        return leaf.takeUnless { identity.isUserName(it) }
    }

    // --------------------------------------------------------------------------------- entries --

    private fun prepareTranscript(file: Path, anonymizer: PathAnonymizer): Prepared {
        val stats = PathAnonymizer.Stats()
        // A line still being written has no newline yet: the fragment is not a message, leave it out.
        val lines = completeLinesOf(readBytes(file))
        val redacted = SessionRedactor.redact(lines.text, redaction)
        val anonymized = anonymizer.anonymizeJsonl(scrubber.scrubJsonl(redacted, scrubStats), stats)
        return Prepared(
            anonymized.toByteArray(StandardCharsets.UTF_8), stats,
            partialLineDropped = lines.partialLineDropped, text = anonymized, isEmpty = lines.text.isBlank(),
        )
    }

    /**
     * A file that is not a transcript: text is anonymized in the encoding it was written in (UTF-8 whatever it
     * holds, NULs included, or UTF-16 with its byte order mark), anything else is copied and scanned. A file
     * above [largeFileBytes] is neither read whole nor anonymized (see [copiedAfterScan]).
     */
    private fun prepareFile(file: Path, anonymizer: PathAnonymizer): Prepared {
        if (Files.size(file) > largeFileBytes) return copiedAfterScan(file, anonymizer)
        val stats = PathAnonymizer.Stats()
        val bytes = readBytes(file)
        val decoded = decodeTextFile(bytes) ?: return Prepared(bytes, stats, binaryHits = anonymizer.scanBytes(bytes))
        val anonymized = anonymizer.anonymizeText(scrubber.scrubText(decoded.text, scrubStats), stats)
        return Prepared(if (anonymized == decoded.text) bytes else decoded.encode(anonymized), stats)
    }

    /**
     * A file too large to hold in memory: it is read once, in chunks, to look for what [PathAnonymizer.scanBytes]
     * looks for, and is then streamed into the archive as it is. A file that is read whole before the first byte
     * is written cannot leave a truncated entry behind. What it holds is not anonymized, which is why a hit is
     * reported like the one of a binary file.
     */
    private fun copiedAfterScan(file: Path, anonymizer: PathAnonymizer): Prepared {
        val buffer = ByteArray(SCAN_CHUNK_BYTES + SCAN_OVERLAP_BYTES)
        var carried = 0
        var hits = 0
        Files.newInputStream(file).use { input ->
            while (hits == 0) {
                val read = input.readNBytes(buffer, carried, SCAN_CHUNK_BYTES)
                if (read == 0) break
                val length = carried + read
                hits = anonymizer.scanBytes(buffer.copyOf(length))
                // A match may straddle two chunks: the end of this one is looked at again with the next.
                carried = minOf(SCAN_OVERLAP_BYTES, length)
                System.arraycopy(buffer, length - carried, buffer, 0, carried)
            }
        }
        return Prepared(bytes = null, stats = PathAnonymizer.Stats(), binaryHits = hits, copiedFrom = file)
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
                val prepared = unlessUnreadable({ unreadableFile(session, file) }) {
                    if (transcripts && file.fileName.toString().endsWith(".jsonl", ignoreCase = true)) {
                        prepareTranscript(file, anonymizer)
                    } else {
                        prepareFile(file, anonymizer)
                    }
                } ?: return FileVisitResult.CONTINUE
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
        val entryName = anonymizer.anonymizeText(name, nameStats)
        val entry = if (prepared.bytes != null) writeRaw(zip, entryName, prepared.bytes) else streamFile(zip, entryName, prepared.copiedFrom!!)
        totals.add(entry, nameStats)
        totals.add(entry, prepared.stats)
        if (prepared.binaryHits > 0) totals.binaryEntries += entry
    }

    /** Writes an entry and returns the name it got: two names that anonymize alike do not collide. */
    private fun writeRaw(zip: ZipOutputStream, name: String, content: ByteArray): String {
        val unique = uniqueEntryName(name, usedNames)
        zip.putNextEntry(ZipEntry(unique))
        zip.write(content)
        zip.closeEntry()
        return unique
    }

    /** [writeRaw] for a file that is copied into the entry as it is, without being held in memory. */
    private fun streamFile(zip: ZipOutputStream, name: String, file: Path): String {
        val unique = uniqueEntryName(name, usedNames)
        zip.putNextEntry(ZipEntry(unique))
        Files.copy(file, zip)
        zip.closeEntry()
        return unique
    }

    // ------------------------------------------------------------------------------ token prefix --

    /** The main transcript and every `.jsonl` file under the auxiliary folder of each session: where a `session_context` line can be. */
    private fun transcriptsOfAllSessions(projectsDir: Path): List<Path> = sessions.flatMap { session ->
        val projectDir = projectsDir.resolve(session.projectFolderName)
        listOf(projectDir.resolve("${session.id}.jsonl")) +
            filesUnder(projectDir.resolve(session.id)).filter { it.fileName.toString().endsWith(".jsonl", ignoreCase = true) }
    }

    /**
     * Whether any file of any session, the name of any of them (it becomes an entry name, and the import renders
     * tokens in entry names) or a value the manifest carries holds [needle].
     */
    private fun sourcesContain(projectsDir: Path, needle: String): Boolean {
        val bytes = needle.toByteArray(StandardCharsets.UTF_8)
        return sessions.any { session ->
            val values = listOfNotNull(session.summary, session.projectPath, session.projectFolderName, session.gitBranch)
            if (values.any { needle in it }) return@any true
            val projectDir = projectsDir.resolve(session.projectFolderName)
            val auxDir = projectDir.resolve(session.id)
            val historyDir = ClaudePaths.fileHistoryDir(home).resolve(session.id)
            val auxFiles = filesUnder(auxDir)
            val historyFiles = filesUnder(historyDir)
            val names = auxFiles.map { auxDir.relativize(it).toString() } + historyFiles.map { historyDir.relativize(it).toString() }
            if (names.any { needle in it }) return@any true
            val sources = listOf(projectDir.resolve("${session.id}.jsonl")) + auxFiles + historyFiles
            sources.any { fileContains(it, bytes) || decodedContains(it, needle) }
        }
    }

    /**
     * Whether a `.jsonl` [file] holds [needle] once its lines are parsed: JSON can write the braces
     * of `{{SP` as unicode escapes, which a scan of the bytes misses and which the import would then take for a
     * token of its own. Only the strings and keys of the parsed lines count, as in the anonymizer. The file is read
     * line by line, and only a line that holds an escape is parsed.
     */
    private fun decodedContains(file: Path, needle: String): Boolean {
        if (!file.fileName.toString().endsWith(".jsonl", ignoreCase = true) || !file.isRegularFile()) return false
        return try {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader -> reader.lineSequence().any { line -> lineHoldsDecoded(line, needle) } }
        } catch (e: IOException) {
            false // not UTF-8: the byte scan has seen what there is
        }
    }

    private fun lineHoldsDecoded(line: String, needle: String): Boolean {
        if (!line.contains(UNICODE_ESCAPE)) return false // nothing is escaped: the byte scan has seen it all
        val element = JsonLines.parse(line) ?: return false
        var found = false
        JsonLines.mapStrings(element, { value -> if (needle in value) found = true; value }, {})
        return found
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

    internal companion object {
        const val TRANSCRIPT_NAME = "transcript.jsonl"

        /** How much of a file too large to read whole is looked at in one go, and how much of the end of it is looked at again. */
        const val SCAN_CHUNK_BYTES = 1 shl 20
        const val SCAN_OVERLAP_BYTES = 4096

        const val TEMPORARY_PREFIX = ".session-porter-"
        const val TEMPORARY_SUFFIX = ".zip.tmp"

        /** A backslash and a `u`: how JSON starts a unicode escape. */
        val UNICODE_ESCAPE = "\\" + "u"
    }
}

/**
 * [name] if [used] does not hold it yet, otherwise the first of `name~2`, `name~3`... (the number goes before the
 * extension) that it does not hold; the name that is returned is added to [used]. Two files whose names anonymize to
 * the same one, or that differ only by case on a case-sensitive file system, stay two entries.
 */
internal fun uniqueEntryName(name: String, used: MutableSet<String>): String {
    if (used.add(name)) return name
    val slash = name.lastIndexOf('/')
    val dot = name.lastIndexOf('.')
    val cut = if (dot > slash + 1) dot else name.length
    var number = 2
    while (true) {
        val candidate = name.substring(0, cut) + "~$number" + name.substring(cut)
        if (used.add(candidate)) return candidate
        number++
    }
}
