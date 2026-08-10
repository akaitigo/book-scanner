package dev.bookscanner.core.contracts

@JvmInline
value class SessionId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "SessionId must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class PageId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "PageId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Identifies which engine family produced or should process a pipeline stage.
 * Per-stage engine selection (AGENTS.md §7) keys off this value.
 */
@JvmInline
value class EngineId(
    val value: String,
) {
    companion object {
        val PRODUCTION = EngineId("production")
        val FROM_SCRATCH = EngineId("from-scratch")
    }
}
