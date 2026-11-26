package com.example.domain.models

enum class DiagnosticStatus {
    IDLE,
    RUNNING,
    PASSED,
    FAILED
}

data class DiagnosticCheckResult(
    val id: String,
    val title: String,
    val status: DiagnosticStatus = DiagnosticStatus.IDLE,
    val summary: String = "Sin ejecutar",
    val latencyMs: Long? = null,
    val timestampMillis: Long = 0L
)

data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
)

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    DEBUG
}
