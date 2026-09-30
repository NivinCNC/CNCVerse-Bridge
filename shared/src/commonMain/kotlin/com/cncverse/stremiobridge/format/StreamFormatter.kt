package com.cncverse.stremiobridge.format

import com.cncverse.stremiobridge.model.StreamInfo
import com.cncverse.stremiobridge.model.StremioStream
import com.cncverse.stremiobridge.state.ServerState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// Template language (PenguPlay / AIOStreams style)
//
//   {path}                                   insert a variable
//   {path::mod::mod(arg)}                    chain modifiers
//   {path::=2160p["yes"||"no"]}              condition → pick a branch
//   {path::exists["only-if-set"]}            single branch (else empty)
//
// Branches are templates themselves and nest freely. Comparisons: = != > >=
// < <= ~ (contains). Modifiers: exists, istrue, isfalse, length, join('sep'),
// default('x'), replace('a','b'), upper, lower, title, trim, first, last,
// truncate(n), bytes (GB), bytes2 (GiB). Escape a literal { } " or \ with \.
// ─────────────────────────────────────────────────────────────────────────────

class TemplateException(message: String) : Exception(message)

private sealed class Node
private class TextNode(val text: String) : Node()
private class ExprNode(
    val path: String,
    val mods: List<Mod>,
    val yes: List<Node>?,
    val no: List<Node>?,
) : Node()

private sealed class Mod
private class CallMod(val name: String, val args: List<String>) : Mod()
private class CmpMod(val op: String, val value: String) : Mod()

/** A parsed template, reusable across renders. */
class Template private constructor(private val nodes: List<Node>) {
    fun render(vars: Map<String, Any?>): String = renderNodes(nodes, vars)

    companion object {
        fun parse(source: String): Template = Template(TemplateParser(source).parseAll())
    }
}

private class TemplateParser(private val s: String) {
    private var i = 0

    fun parseAll(): List<Node> {
        val nodes = parseSeq(inQuote = false)
        if (i < s.length) fail("unexpected '${s[i]}'")
        return nodes
    }

    private fun fail(msg: String): Nothing =
        throw TemplateException("$msg at position ${i + 1}: …${s.substring((i - 15).coerceAtLeast(0), (i + 15).coerceAtMost(s.length))}…")

    private fun startsWith(t: String) = s.startsWith(t, i)

    /** Text + expressions; inside a branch it stops at the closing quote. */
    private fun parseSeq(inQuote: Boolean): List<Node> {
        val out = mutableListOf<Node>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += TextNode(sb.toString()); sb.clear() } }
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "{}\"\\") { sb.append(s[i + 1]); i += 2; continue }
            if (inQuote && c == '"') break
            if (c == '{' && i + 1 < s.length && s[i + 1].isLetter()) { flush(); out += parseExpr(); continue }
            sb.append(c); i++
        }
        flush()
        return out
    }

    private fun parseExpr(): ExprNode {
        i++ // {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
        val path = s.substring(start, i)
        val mods = mutableListOf<Mod>()
        while (startsWith("::")) { i += 2; mods += parseMod() }
        var yes: List<Node>? = null
        var no: List<Node>? = null
        skipWs()
        if (i < s.length && s[i] == '[') {
            i++; skipWs()
            yes = parseQuoted(); skipWs()
            if (startsWith("||")) { i += 2; skipWs(); no = parseQuoted(); skipWs() }
            if (i >= s.length || s[i] != ']') fail("expected ']'")
            i++; skipWs()
        }
        if (i >= s.length || s[i] != '}') fail("expected '}' to close {$path")
        i++
        return ExprNode(path.lowercase(), mods, yes, no)
    }

    private fun parseQuoted(): List<Node> {
        if (i >= s.length || s[i] != '"') fail("expected '\"' to start a branch")
        i++
        val nodes = parseSeq(inQuote = true)
        if (i >= s.length || s[i] != '"') fail("unterminated branch (missing '\"')")
        i++
        return nodes
    }

    private fun skipWs() { while (i < s.length && s[i] == ' ') i++ }

    private fun parseMod(): Mod {
        val ops = listOf(">=", "<=", "!=", "==", "=", ">", "<", "~")
        ops.firstOrNull { startsWith(it) }?.let { op ->
            i += op.length
            val value = if (i < s.length && (s[i] == '\'' || s[i] == '"')) readQuotedArg() else {
                val st = i
                while (i < s.length && !startsWith("::") && s[i] != '[' && s[i] != '}') i++
                s.substring(st, i).trim()
            }
            return CmpMod(if (op == "==") "=" else op, value)
        }
        val st = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        val name = s.substring(st, i).lowercase()
        if (name.isEmpty()) fail("expected a modifier after '::'")
        val args = mutableListOf<String>()
        if (i < s.length && s[i] == '(') {
            i++
            while (true) {
                skipWs()
                if (i >= s.length) fail("unterminated '(' in ::$name")
                if (s[i] == ')') { i++; break }
                if (s[i] == '\'' || s[i] == '"') args += readQuotedArg() else {
                    val a = i
                    while (i < s.length && s[i] != ',' && s[i] != ')') i++
                    args += s.substring(a, i).trim()
                }
                skipWs()
                if (i < s.length && s[i] == ',') i++
            }
        }
        return CallMod(name, args)
    }

    private fun readQuotedArg(): String {
        val q = s[i++]
        val sb = StringBuilder()
        while (i < s.length && s[i] != q) {
            if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 } else sb.append(s[i++])
        }
        if (i >= s.length) fail("unterminated quoted argument")
        i++
        return sb.toString()
    }
}

// ── Evaluation ───────────────────────────────────────────────────────────────

private fun renderNodes(nodes: List<Node>, vars: Map<String, Any?>): String = buildString {
    for (n in nodes) when (n) {
        is TextNode -> append(n.text)
        is ExprNode -> {
            var v: Any? = vars[n.path]
            for (m in n.mods) v = applyMod(m, v)
            if (n.yes != null) append(renderNodes(if (truthy(v)) n.yes else n.no ?: emptyList(), vars))
            else append(asText(v))
        }
    }
}

private fun isEmptyValue(v: Any?): Boolean = when (v) {
    null -> true
    is String -> v.isBlank()
    is Collection<*> -> v.isEmpty()
    else -> false
}

private fun truthy(v: Any?): Boolean = when (v) {
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    else -> !isEmptyValue(v)
}

private fun asText(v: Any?): String = when (v) {
    null -> ""
    is Collection<*> -> v.joinToString(", ") { asText(it) }
    is Double -> if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()
    else -> v.toString()
}

private fun asNumber(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is Collection<*> -> v.size.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

private fun applyMod(m: Mod, v: Any?): Any? = when (m) {
    is CmpMod -> compare(v, m.op, m.value)
    is CallMod -> when (m.name) {
        "exists" -> !isEmptyValue(v)
        "istrue" -> truthy(v)
        "isfalse" -> !truthy(v)
        "length", "count", "len" -> when (v) {
            null -> 0L
            is Collection<*> -> v.size.toLong()
            else -> asText(v).length.toLong()
        }
        "join" -> if (v is Collection<*>) v.joinToString(m.args.firstOrNull() ?: ", ") { asText(it) } else v
        "default" -> if (isEmptyValue(v)) (m.args.firstOrNull() ?: "") else v
        "replace" -> if (v == null) null else asText(v).replace(m.args.getOrNull(0) ?: "", m.args.getOrNull(1) ?: "")
        "upper" -> v?.let { asText(it).uppercase() }
        "lower" -> v?.let { asText(it).lowercase() }
        "title" -> v?.let { asText(it).split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { c -> c.titlecase() } } }
        "trim" -> v?.let { asText(it).trim() }
        "first" -> if (v is List<*>) v.firstOrNull() else v
        "last" -> if (v is List<*>) v.lastOrNull() else v
        "truncate" -> v?.let {
            val t = asText(it); val n = m.args.firstOrNull()?.toIntOrNull() ?: return@let t
            if (t.length > n) t.take(n).trimEnd() + "…" else t
        }
        "bytes" -> asNumber(v)?.let { formatBytes(it, binary = false) }
        "bytes2" -> asNumber(v)?.let { formatBytes(it, binary = true) }
        else -> v // unknown modifiers are ignored rather than breaking the template
    }
}

private fun compare(v: Any?, op: String, raw: String): Boolean {
    if (op == "~") return when (v) {
        is Collection<*> -> v.any { asText(it).contains(raw, ignoreCase = true) }
        null -> false
        else -> asText(v).contains(raw, ignoreCase = true)
    }
    if (v is Collection<*> && (op == "=" || op == "!=")) {
        val has = v.any { asText(it).equals(raw, ignoreCase = true) }
        return if (op == "=") has else !has
    }
    val a = asNumber(v)
    val b = raw.toDoubleOrNull()
    if (a != null && b != null) return when (op) {
        "=" -> a == b; "!=" -> a != b
        ">" -> a > b; ">=" -> a >= b
        "<" -> a < b; "<=" -> a <= b
        else -> false
    }
    if (v == null) return op == "!="
    return when (op) {
        "=" -> asText(v).equals(raw, ignoreCase = true)
        "!=" -> !asText(v).equals(raw, ignoreCase = true)
        else -> false // ordering needs numbers
    }
}

private fun formatBytes(bytes: Double, binary: Boolean): String {
    val base = if (binary) 1024.0 else 1000.0
    val units = if (binary) listOf("B", "KiB", "MiB", "GiB", "TiB") else listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes
    var u = 0
    while (value >= base && u < units.lastIndex) { value /= base; u++ }
    val digits = when { u == 0 || value >= 100 -> 0; value >= 10 -> 1; else -> 2 }
    val text = String.format(java.util.Locale.US, "%.${digits}f", value).let {
        if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it
    }
    return "$text ${units[u]}"
}

// ─────────────────────────────────────────────────────────────────────────────
// Stream variables — structured link data plus what can be parsed from the
// extension's release name (size, languages, tags).
// ─────────────────────────────────────────────────────────────────────────────

/** Request-level context: what the user asked for. */
data class StreamRequestContext(val season: Int? = null, val episode: Int? = null)

object StreamVariables {

    /** Documented variable names (shown in the admin panel). */
    val NAMES = listOf(
        "addon.name", "metadata.title", "metadata.year",
        "stream.resolution", "stream.quality", "stream.specs", "stream.visualTags", "stream.encode",
        "stream.audioTags", "stream.audioChannels", "stream.streamType", "stream.size",
        "stream.languages", "stream.subtitles", "stream.seasonEpisode", "stream.season",
        "stream.episode", "stream.source", "stream.label", "stream.filename", "stream.name", "stream.title",
    )

    private fun rx(p: String) = Regex("(?<![A-Za-z0-9])(?:$p)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

    private val RES_RX = Regex("(?<![0-9])(4320|2160|1440|1080|720|576|480|360|240)[pi](?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
    private val UHD_RX = rx("4k|uhd")
    private val SIZE_RX = Regex("(?<![A-Za-z0-9.])(\\d+(?:[.,]\\d+)?)\\s?(TB|TiB|GB|GiB|MB|MiB)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val SXE_RX = Regex("(?<![A-Za-z0-9])S(\\d{1,2})\\s?E(\\d{1,4})(?![0-9])", RegexOption.IGNORE_CASE)

    private val QUALITY_TAGS = listOf(
        "REMUX" to rx("remux"),
        "BluRay" to rx("blu-?ray|bdrip|brrip|bd-?rip"),
        "WEB-DL" to rx("web-?dl|webdl"),
        "WEBRip" to rx("web-?rip"),
        "HDRip" to rx("hd-?rip"),
        "DVDRip" to rx("dvd-?rip"),
        "HDTV" to rx("hdtv"),
        "HDTC" to rx("hd-?tc"),
        "CAM" to rx("cam|cam-?rip|hd-?cam"),
    )
    private val VISUAL_TAGS = listOf(
        "HDR10+" to Regex("(?<![A-Za-z0-9])HDR10\\+", RegexOption.IGNORE_CASE),
        "HDR10" to rx("hdr10"),
        "HDR" to rx("hdr"),
        "DV" to rx("dv|dovi|dolby[ .-]?vision"),
        "10bit" to rx("10-?bit"),
        "IMAX" to rx("imax"),
        "3D" to rx("3d"),
    )
    private val ENCODE_TAGS = listOf(
        "HEVC" to rx("hevc|x265|h\\.?265"),
        "AVC" to rx("avc|x264|h\\.?264"),
        "AV1" to rx("av1"),
    )
    // Audio codecs are usually glued to their channel count ("DDP5.1", "AAC2.0"),
    // so only a following *letter* ends the match.
    private fun rxAudio(p: String) = Regex("(?<![A-Za-z0-9])(?:$p)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val AUDIO_TAGS = listOf(
        "Atmos" to rxAudio("atmos"),
        "TrueHD" to rxAudio("truehd"),
        "DTS-HD" to rxAudio("dts-?hd(?:[ .-]?ma)?"),
        "DTS" to rxAudio("dts"),
        "DD+" to Regex("(?<![A-Za-z0-9])(?:ddp|dd\\+|e-?ac-?3)", RegexOption.IGNORE_CASE),
        "DD" to rxAudio("dd|ac-?3|dolby[ .-]?digital"),
        "AAC" to rxAudio("aac"),
        "OPUS" to rxAudio("opus"),
    )
    private val CHANNEL_RX = Regex("(?<![0-9.])(7\\.1|5\\.1|2\\.0)(?![0-9])")
    private val LANGUAGES = listOf(
        "Hindi", "English", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Marathi", "Punjabi",
        "Gujarati", "Urdu", "Japanese", "Korean", "Chinese", "Mandarin", "Cantonese", "Spanish", "French",
        "German", "Italian", "Portuguese", "Russian", "Arabic", "Turkish", "Thai", "Indonesian", "Vietnamese",
        "Filipino", "Tagalog", "Polish", "Dutch", "Swedish", "Persian", "Hebrew", "Greek", "Ukrainian",
    ).map { it to rx(it) }

    private fun tags(text: String, table: List<Pair<String, Regex>>, firstOnly: Boolean = false): List<String> {
        val found = table.filter { (_, r) -> r.containsMatchIn(text) }.map { it.first }
        // Keep the most specific of overlapping tags (HDR10+ over HDR10 over HDR, DTS-HD over DTS, DD+ over DD)
        val pruned = found.filterNot { t ->
            (t == "HDR" && ("HDR10" in found || "HDR10+" in found)) ||
                (t == "HDR10" && "HDR10+" in found) ||
                (t == "DTS" && "DTS-HD" in found) ||
                (t == "DD" && "DD+" in found)
        }
        return if (firstOnly) pruned.take(1) else pruned
    }

    private fun resolutionFrom(quality: Int?, vararg texts: String?): String? {
        if (quality != null && quality > 0) return when {
            quality >= 4320 -> "4320p"; quality >= 2160 -> "2160p"; quality >= 1440 -> "1440p"
            quality >= 1080 -> "1080p"; quality >= 720 -> "720p"; quality >= 576 -> "576p"
            quality >= 480 -> "480p"; quality >= 360 -> "360p"; else -> "${quality}p"
        }
        for (t in texts) {
            if (t.isNullOrBlank()) continue
            RES_RX.find(t)?.let { return it.groupValues[1] + "p" }
            if (UHD_RX.containsMatchIn(t)) return "2160p"
        }
        return null
    }

    private fun parseSizeMatch(m: MatchResult): Long? {
        val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].uppercase()) {
            "TB", "TIB" -> 1024.0 * 1024 * 1024 * 1024
            "GB", "GIB" -> 1024.0 * 1024 * 1024
            else -> 1024.0 * 1024
        }
        return (num * mult).toLong()
    }

    /** Size from the link's own name; for multi-quality release names, the size next to our resolution. */
    private fun sizeFrom(resolution: String?, linkText: String?, releaseText: String?): Long? {
        linkText?.let { t -> SIZE_RX.find(t)?.let { return parseSizeMatch(it) } }
        val t = releaseText ?: return null
        if (resolution != null) {
            Regex(Regex.escape(resolution) + "\\s*[\\[(|:-]?\\s*" + SIZE_RX.pattern, RegexOption.IGNORE_CASE)
                .find(t)?.let { m -> SIZE_RX.find(m.value)?.let { return parseSizeMatch(it) } }
        }
        val all = SIZE_RX.findAll(t).toList()
        return if (all.size == 1) parseSizeMatch(all[0]) else null // ambiguous pack listing → unknown
    }

    private fun streamTypeFrom(info: StreamInfo?, stream: StremioStream): String {
        when (info?.linkType?.uppercase()) {
            "M3U8" -> return "HLS"
            "DASH" -> return "DASH"
            "TORRENT", "MAGNET" -> return "Torrent"
        }
        val url = stream.url.orEmpty().lowercase()
        return when {
            stream.infoHash != null -> "Torrent"
            url.contains(".m3u8") || url.contains("/proxy/mpd/") -> "HLS"
            url.contains(".mpd") -> "DASH"
            info?.linkType?.uppercase() == "VIDEO" -> "Direct"
            else -> "Direct"
        }
    }

    /** "Movies4u HubCloud [FSL Server]" → "HubCloud [FSL Server]"; null when it only repeats [addon]. */
    private fun sourceName(source: String?, addon: String?): String? {
        var s = source?.trim().orEmpty()
        if (!addon.isNullOrBlank() && s.startsWith(addon, ignoreCase = true) && s.length > addon.length &&
            !s[addon.length].isLetterOrDigit()) {
            s = s.substring(addon.length).trimStart(' ', '-', ':', '•', '|')
        }
        return s.takeIf { it.isNotEmpty() && !it.equals(addon, ignoreCase = true) }
    }

    /** Link name minus any leading addon/source prefix; null when nothing distinctive is left. */
    private fun labelName(linkName: String?, addon: String?, rawSource: String?, source: String?): String? {
        var s = linkName?.trim().orEmpty()
        for (prefix in listOfNotNull(addon, rawSource, source).filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            if (s.startsWith(prefix, ignoreCase = true) && (s.length == prefix.length || !s[prefix.length].isLetterOrDigit())) {
                s = s.substring(prefix.length).trimStart(' ', '-', ':', '•', '|')
            }
        }
        s = s.trim()
        if (s.isEmpty()) return null
        if (listOfNotNull(addon, rawSource, source).any { it.equals(s, ignoreCase = true) }) return null
        return s
    }

    fun build(stream: StremioStream, ctx: StreamRequestContext): Map<String, Any?> {
        val info = stream.info
        val linkText = info?.linkName ?: stream.title
        // Generic requests prefix the matched release name to `name` ("<release>\n<plugin> - 1080p")
        val releaseText = stream.name?.substringBeforeLast('\n', "")?.takeIf { it.isNotBlank() }
        val allText = listOfNotNull(linkText, releaseText, stream.name).joinToString(" ")

        val resolution = resolutionFrom(info?.quality, linkText, releaseText, stream.name)
        val quality = tags(allText, QUALITY_TAGS, firstOnly = true)
        val visual = tags(allText, VISUAL_TAGS)
        val encode = tags(allText, ENCODE_TAGS, firstOnly = true)
        val audio = tags(allText, AUDIO_TAGS)
        val channels = CHANNEL_RX.find(allText)?.value
        val languages = LANGUAGES.filter { (_, r) -> r.containsMatchIn(allText) }.map { it.first }

        val sxe = SXE_RX.find(allText)
        val season = ctx.season ?: sxe?.groupValues?.get(1)?.toIntOrNull()
        val episode = ctx.episode ?: sxe?.groupValues?.get(2)?.toIntOrNull()
        val seasonEpisode = if (season != null && episode != null)
            listOf("S" + season.toString().padStart(2, '0'), "E" + episode.toString().padStart(2, '0'))
        else emptyList()

        val addon = info?.addonName
            ?: stream.name?.substringAfterLast('\n')?.substringBefore(" - ")?.takeIf { it.isNotBlank() }
        val source = sourceName(info?.source, addon)

        return mapOf(
            "addon.name" to addon,
            "metadata.title" to info?.metadataTitle,
            "metadata.year" to info?.metadataYear?.toLong(),
            "stream.resolution" to resolution,
            "stream.quality" to quality.firstOrNull(),
            "stream.specs" to (quality + visual + encode + audio).distinct(),
            "stream.visualtags" to visual,
            "stream.encode" to encode.firstOrNull(),
            "stream.audiotags" to audio,
            "stream.audiochannels" to channels,
            "stream.streamtype" to streamTypeFrom(info, stream),
            "stream.size" to sizeFrom(resolution, linkText, releaseText),
            "stream.languages" to languages,
            "stream.subtitles" to cleanSubtitles(stream.subtitles),
            "stream.seasonepisode" to seasonEpisode,
            "stream.season" to season?.toLong(),
            "stream.episode" to episode?.toLong(),
            // Server/extractor the extension reports (e.g. "FslServer", "HubCloud");
            // empty when it just repeats the extension name, so templates can use ::exists
            "stream.source" to source,
            // The link's own label from the extension — what tells links of the
            // same item apart (live feeds like "Hindi" / "English", servers,
            // release names). Empty when it only repeats the addon/source name.
            "stream.label" to labelName(info?.linkName, addon, info?.source, source),
            "stream.filename" to linkText,
            "stream.name" to stream.name,
            "stream.title" to stream.title,
        )
    }

    private fun cleanSubtitles(subs: List<com.cncverse.stremiobridge.model.StremioSubtitle>?): List<String> {
        if (subs.isNullOrEmpty()) return emptyList()
        // Strip parenthetical audio suffixes like " (Original Audio)", " (Tamil Audio)", " (Hindi Audio)"
        val cleaned = subs.mapNotNull { sub ->
            val lang = sub.lang.replace(Regex("""\s*\(.*?\)\s*"""), "").trim()
            lang.takeIf { it.isNotBlank() }
        }.distinct()

        if (cleaned.isEmpty()) return emptyList()
        if (cleaned.size <= 3) return cleaned
        return cleaned.take(3) + "+${cleaned.size - 3} more"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Configuration + applying to responses
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class FormatterPreset(
    val id: String,
    val title: String,
    val description: String,
    val nameTemplate: String,
    val descriptionTemplate: String,
)

@Serializable
data class StreamFormatterConfig(
    val enabled: Boolean = false,
    val nameTemplate: String = "",
    val descriptionTemplate: String = "",
)

object StreamFormatter {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    val PRESETS = listOf(
        FormatterPreset(
            id = "cncverse",
            title = "🌀 CNCVerse Modern",
            description = "Balanced with resolution badges, specs, source, and compact audio/subs.",
            nameTemplate = "🌀 CNCVerse {stream.resolution::=2160p[\"❄️ 4K\"||\"{stream.resolution::=1080p[\"🧊 {stream.resolution::default('Auto')}\"||\"🎬 {stream.resolution::default('Auto')}\"]}\"]} • {addon.name}",
            descriptionTemplate = "{stream.seasonEpisode::exists[\"📡 {metadata.title} • {stream.seasonEpisode::join('')}\"||\"🍿 {metadata.title::default('CNCVerse stream')}\"]}\n" +
                "{stream.label::exists[\"🏷️ {stream.label::truncate(90)}\"||\"\"]}\n" +
                "🎞️ {stream.specs::length::>0[\"{stream.specs::join(' • ')}\"||\"{stream.resolution::default('Auto')} • {stream.streamType::default('HLS')}\"]}\n" +
                "🛰️ Source: {addon.name}{stream.source::exists[\" • {stream.source}\"||\"\"]}\n" +
                "{stream.size::>0[\"💾 {stream.size::bytes2::replace('GiB','GB')::replace('MiB','MB')}\"||\"\"]}\n" +
                "{stream.languages::exists[\"🎧 Audio: {stream.languages::join(', ')}\"||\"\"]}\n" +
                "{stream.subtitles::exists[\"💬 Subs: {stream.subtitles::join(', ')}\"||\"\"]}"
        ),
        FormatterPreset(
            id = "torrentio",
            title = "⚡ Torrentio Minimalist",
            description = "Ultra-compact 2-line layout inspired by Torrentio and Cyberflix.",
            nameTemplate = "[CNC] {stream.resolution::default('HD')} • {addon.name}",
            descriptionTemplate = "{metadata.title}{stream.seasonEpisode::exists[\" - {stream.seasonEpisode::join('')}\"||\"\"]}\n" +
                "{stream.specs::join(' ')}{stream.size::>0[\" | {stream.size::bytes2::replace('GiB','GB')::replace('MiB','MB')}\"||\"\"]}{stream.source::exists[\" | {stream.source}\"||\"\"]}\n" +
                "Audio: {stream.languages::default('Multi')}{stream.subtitles::exists[\" | Subs: {stream.subtitles::join(', ')}\"||\"\"]}"
        ),
        FormatterPreset(
            id = "mediafusion",
            title = "💎 MediaFusion Pro",
            description = "Detailed specifications with HDR/DV tags, codecs, and server tags.",
            nameTemplate = "⚡ CNCVerse | {stream.resolution::=2160p[\"💎 4K UHD\"||\"📺 {stream.resolution::default('FHD')}\"]} | {addon.name}",
            descriptionTemplate = "🎬 {metadata.title}{stream.seasonEpisode::exists[\" [{stream.seasonEpisode::join('')}]\"||\"\"]}\n" +
                "⚙️ Specs: {stream.specs::join(' • ')}\n" +
                "📦 Size: {stream.size::>0[\"{stream.size::bytes2::replace('GiB','GB')::replace('MiB','MB')}\"||\"Direct Stream\"]} • Host: {addon.name}\n" +
                "🔊 Audio: {stream.languages::default('Original')}\n" +
                "{stream.subtitles::exists[\"📝 Subs: {stream.subtitles::join(', ')}\"||\"\"]}"
        ),
        FormatterPreset(
            id = "essentials",
            title = "🎯 Pure Essentials",
            description = "Clean essentials without emojis or redundant lines.",
            nameTemplate = "{stream.resolution::default('Auto')} • {stream.streamType::default('Direct')} • {addon.name}",
            descriptionTemplate = "{metadata.title}{stream.seasonEpisode::exists[\" • {stream.seasonEpisode::join('')}\"||\"\"]}\n" +
                "{stream.specs::join(' • ')}\n" +
                "{stream.size::>0[\"Size: {stream.size::bytes2::replace('GiB','GB')::replace('MiB','MB')} • \"||\"\"]}Audio: {stream.languages::default('Default')}"
        )
    )

    const val PRESET_NAME =
        "🌀 CNCVerse {stream.resolution::=2160p[\"❄️ 4K\"||\"{stream.resolution::=1080p[\"🧊 {stream.resolution::default('Auto')}\"||\"🎬 {stream.resolution::default('Auto')}\"]}\"]} • {addon.name}"
    const val PRESET_DESCRIPTION =
        "{stream.seasonEpisode::exists[\"📡 {metadata.title} • {stream.seasonEpisode::join('')}\"||\"🍿 {metadata.title::default('CNCVerse stream')}\"]}\n" +
        "{stream.label::exists[\"🏷️ {stream.label::truncate(90)}\"||\"\"]}\n" +
        "🎞️ {stream.specs::length::>0[\"{stream.specs::join(' • ')}\"||\"{stream.resolution::default('Auto')} • {stream.streamType::default('HLS')}\"]}\n" +
        "🛰️ Source: {addon.name}{stream.source::exists[\" • {stream.source}\"||\"\"]}\n" +
        "{stream.size::>0[\"💾 {stream.size::bytes2::replace('GiB','GB')::replace('MiB','MB')}\"||\"\"]}\n" +
        "{stream.languages::exists[\"🎧 Audio: {stream.languages::join(', ')}\"||\"\"]}\n" +
        "{stream.subtitles::exists[\"💬 Subs: {stream.subtitles::join(', ')}\"||\"\"]}"

    private var file: File? = null

    @Volatile var config: StreamFormatterConfig = StreamFormatterConfig()
        private set
    @Volatile private var nameTpl: Template? = null
    @Volatile private var descTpl: Template? = null

    fun init(cacheDir: String) {
        val f = File(cacheDir, "stream_formatter.json")
        file = f
        val loaded = runCatching { if (f.exists()) json.decodeFromString<StreamFormatterConfig>(f.readText()) else null }
            .onFailure { ServerState.warn("Stream formatter config unreadable: ${it.message}") }
            .getOrNull() ?: StreamFormatterConfig()
        runCatching { install(loaded) }.onFailure {
            ServerState.warn("Stream formatter disabled — template error: ${it.message}")
            config = loaded.copy(enabled = false)
        }
    }

    /** Validates both templates; throws [TemplateException] naming the broken one. */
    fun validate(cfg: StreamFormatterConfig): Pair<Template?, Template?> {
        val n = cfg.nameTemplate.takeIf { it.isNotBlank() }?.let {
            try { Template.parse(it) } catch (e: TemplateException) { throw TemplateException("Name template: ${e.message}") }
        }
        val d = cfg.descriptionTemplate.takeIf { it.isNotBlank() }?.let {
            try { Template.parse(it) } catch (e: TemplateException) { throw TemplateException("Description template: ${e.message}") }
        }
        return n to d
    }

    private fun install(cfg: StreamFormatterConfig) {
        val (n, d) = validate(cfg)
        nameTpl = n
        descTpl = d
        config = cfg
    }

    /** Validates, activates and persists [cfg]. Throws [TemplateException] when a template is invalid. */
    fun save(cfg: StreamFormatterConfig) {
        install(cfg)
        file?.let { f -> runCatching { f.writeText(json.encodeToString(StreamFormatterConfig.serializer(), cfg)) } }
    }

    /** Renders [stream] with the given templates (null template → keep original). */
    fun format(stream: StremioStream, ctx: StreamRequestContext, name: Template?, desc: Template?): StremioStream {
        if (name == null && desc == null) return stream
        return try {
            val vars = StreamVariables.build(stream, ctx)
            val newName = name?.render(vars)?.trim()?.takeIf { it.isNotEmpty() } ?: stream.name
            val newDesc = desc?.render(vars)
                ?.lines()?.map { it.trimEnd() }?.filter { it.isNotBlank() }?.joinToString("\n")
                ?.takeIf { it.isNotEmpty() } ?: stream.title
            stream.copy(name = newName, title = newDesc)
        } catch (e: Throwable) {
            stream // never let a template problem drop a stream
        }
    }

    /** Applies the saved templates to a stream response when the formatter is enabled. */
    fun apply(streams: List<StremioStream>, ctx: StreamRequestContext): List<StremioStream> {
        if (!config.enabled) return streams
        val n = nameTpl
        val d = descTpl
        if (n == null && d == null) return streams
        return streams.map { format(it, ctx, n, d) }
    }

    /** Season/episode from a Stremio stream id like "tt0903747:1:3" or "tmdb:1396:1:3". */
    fun contextFromId(type: String, id: String): StreamRequestContext {
        if (type != "series") return StreamRequestContext()
        val parts = id.split(":")
        val nums = parts.drop(if (parts.firstOrNull() == "tmdb") 2 else 1).mapNotNull { it.toIntOrNull() }
        return if (nums.size >= 2) StreamRequestContext(season = nums[0], episode = nums[1]) else StreamRequestContext()
    }

    /** Sample streams for the admin preview. */
    fun samples(): List<Pair<StremioStream, StreamRequestContext>> = listOf(
        StremioStream(
            name = "Interstellar (2014) iMAX BluRay [Hindi DD5.1 + English] 4K 1080p\nHDHub4U - 2160p",
            title = "Interstellar.2014.IMAX.2160p.BluRay.HEVC.10bit.HDR.DDP5.1.Atmos [18.5GB]",
            url = "https://example.com/interstellar.mkv",
            subtitles = listOf(com.cncverse.stremiobridge.model.StremioSubtitle("en", "English", "https://example.com/en.vtt")),
            info = StreamInfo(addonName = "HDHub4U", quality = 2160, linkType = "VIDEO", source = "HubCloud",
                linkName = "Interstellar.2014.IMAX.2160p.BluRay.HEVC.10bit.HDR.DDP5.1.Atmos [18.5GB]",
                metadataTitle = "Interstellar", metadataYear = 2014),
        ) to StreamRequestContext(),
        StremioStream(
            name = "Slow Horses\nPrime Video - 1080p",
            title = "Slow Horses S01E03 1080p WEB-DL [Hindi + English] x264",
            url = "https://example.com/slow-horses/master.m3u8",
            info = StreamInfo(addonName = "Prime Video", quality = 1080, linkType = "M3U8", source = "FslServer",
                linkName = "Slow Horses S01E03 1080p WEB-DL [Hindi + English] x264",
                metadataTitle = "Slow Horses", metadataYear = 2022),
        ) to StreamRequestContext(season = 1, episode = 3),
        StremioStream(
            name = "JioTV",
            title = "Star Sports 1 HD",
            url = "https://example.com/live/index.m3u8",
            info = StreamInfo(addonName = "JioTV", linkType = "M3U8", linkName = "Star Sports 1 HD"),
        ) to StreamRequestContext(),
    )
}
