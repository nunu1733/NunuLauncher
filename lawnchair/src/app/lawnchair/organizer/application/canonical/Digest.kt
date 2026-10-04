package app.lawnchair.organizer.application.canonical

import java.security.MessageDigest

/**
 * Domain-separated SHA-256 digests used for [RevisionId], checkpoint integrity,
 * action-set fingerprints, and read-after-write checksums.
 *
 * Pure wrapper over [MessageDigest]; allocation-explicit; deterministic across
 * locale, timezone, and thread scheduling. No platform/Android types cross
 * this seam.
 *
 * Issue #14 Stage B step 1.
 */
object Digest {

    /** Raw 32-byte SHA-256 over the exact bytes produced by a [DigestSink]. */
    fun sha256(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_DIGEST_INPUT_BYTES) {
            "Digest input exceeds $MAX_DIGEST_INPUT_BYTES bytes"
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    /** Hex lowercase rendering of a 32-byte digest, suitable for [RevisionId]. */
    fun hexLowerCase(bytes: ByteArray): String {
        require(bytes.size == HASH_BYTES_PUBLIC) {
            "Digest must be $HASH_BYTES_PUBLIC bytes, got ${bytes.size}"
        }
        val out = CharArray(bytes.size * 2)
        var i = 0
        while (i < bytes.size) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_LOWERCASE[v ushr 4]
            out[i * 2 + 1] = HEX_LOWERCASE[v and 0x0F]
            i += 1
        }
        return String(out)
    }

    /**
     * Open a tagged [DigestSink]. The tag is written length-prefixed first and
     * participates in the digest bytes, so two sinks with different tags cannot
     * collide even if their bodies are byte-identical.
     */
    fun tagged(tag: String): DigestSink {
        val md = MessageDigest.getInstance("SHA-256")
        val tagBytes = tag.toByteArray(Charsets.UTF_8)
        require(tagBytes.isNotEmpty()) { "Digest tag must be non-empty" }
        require(tagBytes.size <= MAX_TAG_BYTES) { "Digest tag exceeds $MAX_TAG_BYTES bytes" }
        md.update(intLengthPrefix(tagBytes.size))
        md.update(tagBytes)
        return DigestSink(md)
    }

    private fun intLengthPrefix(value: Int): ByteArray {
        require(value >= 0) { "Length prefix must be non-negative" }
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }

    /**
     * Streaming sink for canonical bytes. Each write is length-prefixed so the
     * encoding is self-delimiting and cannot be re-ordered.
     */
    class DigestSink internal constructor(private val md: MessageDigest) {
        private var bytesWritten: Long = 0L

        fun bytes(value: ByteArray): DigestSink {
            require(value.size <= MAX_FIELD_BYTES) { "Single field exceeds $MAX_FIELD_BYTES bytes" }
            md.update(intLengthPrefix(value.size))
            md.update(value)
            bytesWritten += 4L + value.size
            checkBounds()
            return this
        }

        fun text(value: String): DigestSink = bytes(value.toByteArray(Charsets.UTF_8))

        fun boolean(value: Boolean): DigestSink = byte(if (value) 1 else 0)

        fun byte(value: Int): DigestSink {
            val v = value and 0xFF
            md.update(intLengthPrefix(1))
            md.update(v.toByte())
            bytesWritten += 5L
            checkBounds()
            return this
        }

        fun int(value: Int): DigestSink {
            md.update(intLengthPrefix(4))
            md.update(
                byteArrayOf(
                    (value ushr 24).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 8).toByte(),
                    value.toByte(),
                ),
            )
            bytesWritten += 8L
            checkBounds()
            return this
        }

        fun long(value: Long): DigestSink {
            md.update(intLengthPrefix(8))
            md.update(
                byteArrayOf(
                    (value ushr 56).toByte(),
                    (value ushr 48).toByte(),
                    (value ushr 40).toByte(),
                    (value ushr 32).toByte(),
                    (value ushr 24).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 8).toByte(),
                    value.toByte(),
                ),
            )
            bytesWritten += 12L
            checkBounds()
            return this
        }

        fun result(): ByteArray {
            val out = md.digest()
            check(out.size == HASH_BYTES_PUBLIC) {
                "Digest produced ${out.size} bytes, expected $HASH_BYTES_PUBLIC"
            }
            return out
        }

        private fun checkBounds() {
            check(bytesWritten <= MAX_DIGEST_INPUT_BYTES) {
                "Digest input exceeded $MAX_DIGEST_INPUT_BYTES bytes"
            }
        }
    }

    /**
     * Digest kind tags. Each is a stable canonical literal; do not change the
     * string form across releases — that would invalidate persisted recovery
     * records and revisions.
     */
    object Kind {
        const val PRE_STATE: String = "organizer/pre-state/v1"
        const val INTENDED_POST_STATE: String = "organizer/intended-post-state/v1"
        const val ACTION_SET: String = "organizer/action-set/v1"
        const val RECOVERY_ACTION_SET: String = "organizer/recovery-action-set/v1"
        const val REVIEWED_CURRENT_STATE: String = "organizer/reviewed-current-state/v1"
        const val AUTHORITATIVE_STATE: String = "organizer/authoritative-state/v1"
        const val RECOVERY_PAYLOAD_CHECKSUM: String = "organizer/recovery-payload-checksum/v1"
    }

    const val HASH_BYTES_PUBLIC: Int = 32

    private const val MAX_TAG_BYTES: Int = 64
    private const val MAX_FIELD_BYTES: Int = 8 * 1024 * 1024
    private const val MAX_DIGEST_INPUT_BYTES: Long = 64L * 1024L * 1024L

    private val HEX_LOWERCASE: CharArray = "0123456789abcdef".toCharArray()
}
