package zone.vao.voxen.network

import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Envelope {

    const val VERSION = "v1"
    const val MAX_BYTES = 256 * 1024

    private const val DOMAIN = "voxen-network-v1"
    private const val ALGORITHM = "HmacSHA256"
    private const val SEPARATOR = '.'
    private val hex = HexFormat.of()

    sealed interface Result {
        data class Ok(val payload: String) : Result

        enum class Rejected : Result {
            TOO_BIG,
            MALFORMED,
            VERSION,
            SIGNATURE,
            EXPIRED,
        }
    }

    fun derive(base: String): String = sign(DOMAIN, base)

    fun wrap(payload: String, secret: String, now: Long = System.currentTimeMillis()): String {
        val signed = "$VERSION$SEPARATOR$now$SEPARATOR$payload"
        return "$VERSION$SEPARATOR${sign(signed, secret)}$SEPARATOR$now$SEPARATOR$payload"
    }

    fun unwrap(
        raw: String,
        secret: String,
        maxAgeMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): Result {
        if (raw.length > MAX_BYTES) return Result.Rejected.TOO_BIG

        val version = raw.substringBefore(SEPARATOR, "")
        val afterVersion = raw.indexOf(SEPARATOR) + 1
        if (afterVersion <= 0) return Result.Rejected.MALFORMED
        if (version != VERSION) return Result.Rejected.VERSION

        val afterSignature = raw.indexOf(SEPARATOR, afterVersion)
        if (afterSignature < 0) return Result.Rejected.MALFORMED
        val signature = raw.substring(afterVersion, afterSignature)

        val afterStamp = raw.indexOf(SEPARATOR, afterSignature + 1)
        if (afterStamp < 0) return Result.Rejected.MALFORMED
        val sentAt = raw.substring(afterSignature + 1, afterStamp).toLongOrNull() ?: return Result.Rejected.MALFORMED
        val payload = raw.substring(afterStamp + 1)

        if (secret.isNotEmpty()) {
            val expected = sign("$version$SEPARATOR$sentAt$SEPARATOR$payload", secret)
            if (expected.isEmpty()) return Result.Rejected.SIGNATURE
            val received = runCatching { hex.parseHex(signature) }.getOrNull() ?: return Result.Rejected.SIGNATURE
            if (!MessageDigest.isEqual(received, hex.parseHex(expected))) return Result.Rejected.SIGNATURE
        }
        if (maxAgeMillis > 0 && Math.abs(now - sentAt) > maxAgeMillis) return Result.Rejected.EXPIRED

        return Result.Ok(payload)
    }

    private fun sign(text: String, secret: String): String {
        if (secret.isEmpty()) return ""
        val mac = runCatching {
            Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret.toByteArray(), ALGORITHM)) }
        }.getOrNull() ?: return ""
        return hex.formatHex(mac.doFinal(text.toByteArray()))
    }
}
