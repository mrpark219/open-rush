package me.park.openrush.reservation.application.port.output

fun interface ReleasePurchaseQuotaPort {
    /** 예매 종료 전환 성공 후 같은 트랜잭션에서 호출한다. 수량이 부족하거나 행이 없으면 실패한다. */
    fun releaseQuota(userId: Long, concertId: Long, quantity: Int)
}
