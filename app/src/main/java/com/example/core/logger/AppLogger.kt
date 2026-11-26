package com.example.core.logger

import com.example.domain.models.LogEntry
import com.example.domain.models.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

object AppLogger {
    private const val MAX_ENTRIES = 350
    private val sequence = AtomicLong(0L)
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Volatile
    var verboseEnabled: Boolean = false

    private val secretPatterns = listOf(
        Regex("""(?i)(password|passwd|pwd|passphrase|token|secret|authorization|proxy-authorization)\s*[:=]\s*[^\s,;]+"""),
        Regex("""(?i)bearer\s+[A-Za-z0-9\-._~+/]+=*"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
        Regex("""(?i)(ssh|socks5|https?)://[^:@\s]+:([^@\s]+)@""")
    )

    fun sanitize(raw: String): String {
        var result = raw
        result = secretPatterns[2].replace(result, "[PRIVATE_KEY_REDACTED]")
        result = secretPatterns[1].replace(result, "Bearer [REDACTED]")
        result = secretPatterns[3].replace(result) { match ->
            val full = match.value
            val pass = match.groupValues[2]
            full.replace(":$pass@", ":[REDACTED]@")
        }
        result = secretPatterns[0].replace(result) { match ->
            val key = match.groupValues[1]
            "$key=[REDACTED]"
        }
        return result
    }

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val detail = throwable?.message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        log(LogLevel.ERROR, tag, "$message$detail")
    }

    fun debug(tag: String, message: String) {
        if (verboseEnabled) {
            log(LogLevel.DEBUG, tag, message)
        }
    }

    private fun log(level: LogLevel, tag: String, rawMessage: String) {
        val entry = LogEntry(
            id = sequence.incrementAndGet(),
            timestampMillis = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = sanitize(rawMessage)
        )
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            _logs.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _logs.value = emptyList()
        }
    }
}
