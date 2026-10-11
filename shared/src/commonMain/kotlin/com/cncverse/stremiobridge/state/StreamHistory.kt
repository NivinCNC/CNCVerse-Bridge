package com.cncverse.stremiobridge.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One answered stream request: what was asked, what each extension found, and the exact list that was sent. */
@Serializable
data class HistoryEntry(
    val time: Long,
    val type: String,
    val id: String,
    val title: String,
    /** "S2 E2" for series / anime, "" for movies */
    val episode: String = "",
    /** All links found before filtering */
    val found: Int,
    /** Links sent to the client */
    val shown: Int,
    val tookMs: Long = 0,
    /** "AnimePahe: 4" */
    val providers: List<String> = emptyList(),
    /** Exactly as the client received them: name, a " | " and the description (new lines shown as " / ") */
    val lines: List<String> = emptyList(),
)

object StreamHistory {
    private const val MAX = 150
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries
    @Volatile private var file: File? = null
    private val titles = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun init(cacheDir: String) {
        if (file != null) return
        file = File(cacheDir, "stream_history.json")
        runCatching {
            val f = file ?: return
            if (f.exists()) _entries.value = json.decodeFromString<List<HistoryEntry>>(f.readText())
        }
    }

    /** The resolved title of [id] (so the history shows names, not ids). */
    fun noteTitle(id: String, title: String) {
        if (titles.size > 500) titles.clear()
        titles[id] = title
    }

    fun titleFor(id: String): String? = titles[id]

    @Synchronized
    fun add(e: HistoryEntry) {
        _entries.value = (listOf(e) + _entries.value).take(MAX)
        runCatching { file?.writeText(json.encodeToString(_entries.value)) }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        runCatching { file?.delete() }
    }
}
