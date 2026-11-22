package me.park.openrush.support.pg

data class RecordedPgRequest(
    val method: String,
    val path: String,
    val body: String,
    val idempotencyKey: String?,
    val remotePort: Int,
) {
    /** 커넥션이 달라져도 메서드, 경로, 본문, 멱등 키가 같은 요청인지 확인한다. */
    fun hasSameContentAs(other: RecordedPgRequest): Boolean =
        method == other.method && path == other.path && body == other.body &&
            idempotencyKey == other.idempotencyKey
}
