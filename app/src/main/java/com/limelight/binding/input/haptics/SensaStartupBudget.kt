package com.limelight.binding.input.haptics

/** One deadline per exchange plus a bounded allowance for scheduling and metadata parsing. */
internal object SensaStartupBudget {
    const val MAX_METADATA_BYTES = 4096
    const val CHUNK_BYTES = 50
    const val TRANSFER_MS = 150L
    fun remainingAfterSize(metadataBytes: Int): Long {
        require(metadataBytes in 1..MAX_METADATA_BYTES)
        val chunks = (metadataBytes + CHUNK_BYTES - 1) / CHUNK_BYTES
        // Metadata chunks, mode read, optional mode write and initial silence.
        return (chunks + 3) * TRANSFER_MS + 500
    }
    val maximumMs: Long get() = TRANSFER_MS + remainingAfterSize(MAX_METADATA_BYTES)
}
