package com.example.engines.http

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

data class PayloadMacroContext(
    val host: String,
    val port: Int,
    val resolvedIp: String = host,
    val sshEndpoint: String = "$host:$port",
    val method: String = "CONNECT",
    val protocol: String = "HTTP/1.1",
    val username: String = "",
    val password: String = "",
    val userAgent: String = RawPayloadParser.DEFAULT_USER_AGENT
)

data class PayloadChunk(
    val bytes: ByteArray,
    val delayAfterMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PayloadChunk) return false
        return bytes.contentEquals(other.bytes) && delayAfterMs == other.delayAfterMs
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + delayAfterMs.hashCode()
}

data class ParsedPayloadPlan(
    val expandedText: String,
    val chunks: List<PayloadChunk>,
    val expectedHttpResponses: Int,
    val isWebSocketUpgrade: Boolean
)

object RawPayloadParser {
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val rotateRegex = Regex("""\[rotate=([^\]]*)]""", RegexOption.IGNORE_CASE)
    private val randomRegex = Regex("""\[random=([^\]]*)]""", RegexOption.IGNORE_CASE)
    private val rawRegex = Regex("""\[raw]""", RegexOption.IGNORE_CASE)
    private val realRawRegex = Regex("""\[real_raw]""", RegexOption.IGNORE_CASE)
    private val netDataRegex = Regex("""\[netData]""", RegexOption.IGNORE_CASE)
    private val hostPortRegex = Regex("""\[host_port]""", RegexOption.IGNORE_CASE)
    private val hostRegex = Regex("""\[host]""", RegexOption.IGNORE_CASE)
    private val portRegex = Regex("""\[port]""", RegexOption.IGNORE_CASE)
    private val ipRegex = Regex("""\[ip]""", RegexOption.IGNORE_CASE)
    private val sshRegex = Regex("""\[ssh]""", RegexOption.IGNORE_CASE)
    private val protocolRegex = Regex("""\[protocol]""", RegexOption.IGNORE_CASE)
    private val methodRegex = Regex("""\[method]""", RegexOption.IGNORE_CASE)
    private val authRegex = Regex("""\[auth]""", RegexOption.IGNORE_CASE)
    private val uaRegex = Regex("""\[ua]""", RegexOption.IGNORE_CASE)

    private val crlfRegex = Regex("""\[crlf]""", RegexOption.IGNORE_CASE)
    private val lfcrRegex = Regex("""\[lfcr]""", RegexOption.IGNORE_CASE)
    private val lfRegex = Regex("""\[lf]""", RegexOption.IGNORE_CASE)
    private val crRegex = Regex("""\[cr]""", RegexOption.IGNORE_CASE)

    private val splitTagRegex = Regex(
        """\[(split|instant_split|splitNoDelay|delay_split|split_delay=(\d+))]""",
        RegexOption.IGNORE_CASE
    )
    private val singleNewlineTokenRegex = Regex(
        """^(?:\[crlf]|\[lfcr]|\[lf]|\[cr]|\r\n|\n\r|\n|\r)""",
        RegexOption.IGNORE_CASE
    )
    private val leadingSplitRegex = Regex(
        """^\[(?:split|instant_split|splitNoDelay|delay_split|split_delay=\d+)]""",
        RegexOption.IGNORE_CASE
    )
    private val httpVersionRegex = Regex("""HTTP/\d(?:\.\d)?""", RegexOption.IGNORE_CASE)

    private val rotationCounter = AtomicInteger(0)

    fun parse(
        rawPayload: String,
        context: PayloadMacroContext,
        random: Random = Random.Default
    ): ParsedPayloadPlan {
        if (rawPayload.isEmpty()) {
            return ParsedPayloadPlan(
                expandedText = "",
                chunks = emptyList(),
                expectedHttpResponses = 0,
                isWebSocketUpgrade = false
            )
        }

        val cleanHost = context.host.trim()
        val hostPort = "$cleanHost:${context.port}"
        val sshTarget = context.sshEndpoint.ifBlank { hostPort }
        val resolvedIp = context.resolvedIp.ifBlank { cleanHost }
        val method = context.method.ifBlank { "CONNECT" }
        val protocol = context.protocol.ifBlank { "HTTP/1.1" }
        val authHeader = if (context.username.isNotEmpty() || context.password.isNotEmpty()) {
            val rawCreds = "${context.username}:${context.password}".toByteArray(StandardCharsets.UTF_8)
            "Proxy-Authorization: Basic ${encodeBase64(rawCreds)}"
        } else {
            ""
        }

        // 1. Dynamic rotation and randomness first (nested macros inside options will expand next)
        var text = rotateRegex.replace(rawPayload) { match ->
            val options = match.groupValues[1]
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (options.isEmpty()) "" else {
                val idx = (rotationCounter.getAndIncrement() and 0x7FFFFFFF) % options.size
                options[idx]
            }
        }
        text = randomRegex.replace(text) { match ->
            val options = match.groupValues[1]
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (options.isEmpty()) "" else options[random.nextInt(options.size)]
        }

        // 2. [raw] and [real_raw] with duplicate blank-line normalization
        text = expandRawMacros(text, rawRegex, hostPort, method, protocol)
        text = expandRawMacros(text, realRawRegex, sshTarget, method, protocol)

        // 3. Connection & HTTP metadata macros
        text = netDataRegex.replace(text) { "$method $hostPort $protocol" }
        text = hostPortRegex.replace(text) { hostPort }
        text = hostRegex.replace(text) { cleanHost }
        text = portRegex.replace(text) { context.port.toString() }
        text = ipRegex.replace(text) { resolvedIp }
        text = sshRegex.replace(text) { sshTarget }
        text = protocolRegex.replace(text) { protocol }
        text = methodRegex.replace(text) { method }
        text = authRegex.replace(text) { authHeader }
        text = uaRegex.replace(text) { context.userAgent }

        // 4. Line breaks & control characters (preserving intentional [crlf] vs [lf] vs [cr] vs [lfcr])
        text = crlfRegex.replace(text, "\r\n")
        text = lfcrRegex.replace(text, "\n\r")
        text = lfRegex.replace(text, "\n")
        text = crRegex.replace(text, "\r")

        // 5. Split & delay segmentation
        val chunks = mutableListOf<PayloadChunk>()
        var cursor = 0
        for (match in splitTagRegex.findAll(text)) {
            val segmentText = text.substring(cursor, match.range.first)
            val tagName = match.groupValues[1].lowercase()
            val delayMs = when {
                tagName == "split" -> random.nextInt(20, 51).toLong()
                tagName == "instant_split" || tagName == "splitnodelay" -> random.nextInt(1, 6).toLong()
                tagName == "delay_split" -> random.nextInt(150, 301).toLong()
                tagName.startsWith("split_delay=") -> {
                    match.groupValues[2].toLongOrNull()?.coerceIn(0L, 60_000L) ?: 0L
                }
                else -> 0L
            }
            chunks.add(
                PayloadChunk(
                    bytes = segmentText.toByteArray(StandardCharsets.ISO_8859_1),
                    delayAfterMs = delayMs
                )
            )
            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            val tail = text.substring(cursor)
            chunks.add(
                PayloadChunk(
                    bytes = tail.toByteArray(StandardCharsets.ISO_8859_1),
                    delayAfterMs = 0L
                )
            )
        }

        val expandedWithoutSplits = splitTagRegex.replace(text, "")
        val expectedResponses = httpVersionRegex.findAll(expandedWithoutSplits).count().coerceAtLeast(1)
        val isWsUpgrade = expandedWithoutSplits.contains("upgrade:", ignoreCase = true) &&
            expandedWithoutSplits.contains("websocket", ignoreCase = true)

        return ParsedPayloadPlan(
            expandedText = expandedWithoutSplits,
            chunks = chunks,
            expectedHttpResponses = expectedResponses,
            isWebSocketUpgrade = isWsUpgrade
        )
    }

    private fun expandRawMacros(
        input: String,
        pattern: Regex,
        authority: String,
        method: String,
        protocol: String
    ): String {
        val matches = pattern.findAll(input).toList()
        if (matches.isEmpty()) return input

        val sb = StringBuilder(input.length + 64)
        var cursor = 0
        val base = "$method $authority $protocol\r\nHost: $authority"

        for (match in matches) {
            sb.append(input, cursor, match.range.first)
            sb.append(base)

            val afterIndex = match.range.last + 1
            val firstNl = singleNewlineTokenRegex.find(input.substring(afterIndex))
            if (firstNl == null) {
                sb.append("\r\n\r\n")
            } else {
                val afterFirstNl = afterIndex + firstNl.value.length
                val secondNl = singleNewlineTokenRegex.find(input.substring(afterFirstNl))
                if (secondNl != null) {
                    // User payload already provides two line breaks immediately after [raw]/[real_raw]
                } else {
                    val tailAfterFirst = input.substring(afterFirstNl)
                    if (tailAfterFirst.isEmpty() || leadingSplitRegex.containsMatchIn(tailAfterFirst)) {
                        // Single trailing newline at end of request: complete the blank line
                        sb.append("\r\n")
                    }
                    // Otherwise another header follows the single newline, so no extra CRLF needed
                }
            }
            cursor = afterIndex
        }
        sb.append(input, cursor, input.length)
        return sb.toString()
    }

    internal fun encodeBase64(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val hasB1 = i + 1 < bytes.size
            val hasB2 = i + 2 < bytes.size
            val b1 = if (hasB1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) bytes[i + 2].toInt() and 0xFF else 0
            val triplet = (b0 shl 16) or (b1 shl 8) or b2

            out.append(alphabet[(triplet ushr 18) and 0x3F])
            out.append(alphabet[(triplet ushr 12) and 0x3F])
            out.append(if (hasB1) alphabet[(triplet ushr 6) and 0x3F] else '=')
            out.append(if (hasB2) alphabet[triplet and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }
}
