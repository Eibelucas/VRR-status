package de.luca.mitfahrt.shared

import java.time.Instant
import java.util.UUID

/** A test handshake is separate from both journey snapshots and real arrival alerts. */
object HapticTestProtocol {
    const val REQUEST = "/mitfahrt/haptic-test/v1"
    const val ACK = "/mitfahrt/haptic-test-ack/v1"
    private const val MAX_BYTES = 256

    data class Request(val id: String, val issuedAt: Instant)
    data class Acknowledgement(val request: Request, val triggered: Boolean)

    fun create(now: Instant = Instant.now()): Request = Request(UUID.randomUUID().toString(), Instant.ofEpochMilli(now.toEpochMilli()))

    fun encode(request: Request): ByteArray {
        validateId(request.id)
        return "${request.id}\n${request.issuedAt.toEpochMilli()}".toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray, now: Instant): Request? = runCatching {
        require(bytes.size in 1..MAX_BYTES)
        val parts = bytes.toString(Charsets.UTF_8).split('\n')
        require(parts.size == 2)
        parseRequest(parts[0], parts[1], now)
    }.getOrNull()

    fun encodeAck(request: Request, triggered: Boolean): ByteArray =
        encode(request) + (if (triggered) "\ntriggered" else "\nunavailable").toByteArray(Charsets.UTF_8)

    fun decodeAck(bytes: ByteArray, now: Instant): Acknowledgement? = runCatching {
        require(bytes.size in 1..MAX_BYTES)
        val parts = bytes.toString(Charsets.UTF_8).split('\n')
        require(parts.size == 3 && parts[2] in setOf("triggered", "unavailable"))
        Acknowledgement(parseRequest(parts[0], parts[1], now), parts[2] == "triggered")
    }.getOrNull()

    fun matchingAck(bytes: ByteArray, now: Instant, request: Request, sourceNode: String,
        expectedNodes: Set<String>): Acknowledgement? {
        if (sourceNode !in expectedNodes) return null
        return decodeAck(bytes, now)?.takeIf { it.request == request }
    }

    private fun parseRequest(id: String, timestamp: String, now: Instant): Request {
        validateId(id)
        val issued = Instant.ofEpochMilli(timestamp.toLong())
        val age = java.time.Duration.between(issued, now).toMillis()
        require(age in -5_000..15_000)
        return Request(id, issued)
    }

    private fun validateId(id: String) {
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }
}
