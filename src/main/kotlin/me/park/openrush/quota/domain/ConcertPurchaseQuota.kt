package me.park.openrush.quota.domain

import me.park.openrush.quota.domain.exception.QuotaRuleViolationException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import me.park.openrush.support.persistence.BaseEntity

@Entity
@Table(
    name = "concert_purchase_quotas", uniqueConstraints = [
        UniqueConstraint(name = "uk_user_concert_quota", columnNames = ["user_id", "concert_id"]),
    ]
)
class ConcertPurchaseQuota(
    userId: Long,
    concertId: Long
) : BaseEntity() {
    @field:Column(name = "user_id", nullable = false)
    var userId: Long = userId
        protected set

    @field:Column(name = "concert_id", nullable = false)
    var concertId: Long = concertId
        protected set

    @field:Column(nullable = false)
    var allocatedQuantity: Int = 0
        protected set

    init {
        require(userId > 0) { "사용자 ID는 양수여야 합니다." }
    }

    fun allocate(quantity: Int, maxTicketsPerUser: Int) {
        require(quantity > 0) { "확보할 수량은 양수여야 합니다." }
        checkQuota(
            quantity <= maxTicketsPerUser - allocatedQuantity,
            "콘서트별 1인 ${maxTicketsPerUser}매 한도를 초과했습니다."
        )
        allocatedQuantity += quantity
    }

    /** 예매 종료 전환 성공 후 같은 트랜잭션에서 한 번만 호출한다. */
    fun release(quantity: Int) {
        require(quantity > 0) { "반환할 수량은 양수여야 합니다." }
        checkQuota(quantity <= allocatedQuantity, "확보한 한도보다 많이 반환할 수 없습니다.")
        allocatedQuantity -= quantity
    }
}

private fun checkQuota(condition: Boolean, message: String) {
    if (!condition) throw QuotaRuleViolationException(message)
}
