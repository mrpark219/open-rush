package me.park.openrush.reservation.domain

import jakarta.persistence.*
import me.park.openrush.concert.domain.Concert

@Entity
@Table(
    name = "concert_purchase_quotas", uniqueConstraints = [
        UniqueConstraint(name = "uk_user_concert_quota", columnNames = ["user_id", "concert_id"]),
    ]
)
class ConcertPurchaseQuota(userId: Long, concert: Concert) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(name = "user_id", nullable = false)
    var userId: Long = userId
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "concert_id", nullable = false)
    var concert: Concert = concert
        protected set

    @field:Column(nullable = false)
    var allocatedQuantity: Int = 0
        protected set

    init {
        require(userId > 0) { "사용자 ID는 양수여야 합니다." }
    }

    fun allocate(quantity: Int) {
        require(quantity > 0) { "확보할 수량은 양수여야 합니다." }
        checkRule(quantity <= concert.maxTicketsPerUser - allocatedQuantity,
            "콘서트별 1인 ${concert.maxTicketsPerUser}매 한도를 초과했습니다.")
        allocatedQuantity += quantity
    }

    /** 예매 종료 전환 성공 후 같은 트랜잭션에서 한 번만 호출한다. */
    fun release(quantity: Int) {
        require(quantity > 0) { "반환할 수량은 양수여야 합니다." }
        checkRule(quantity <= allocatedQuantity, "확보한 한도보다 많이 반환할 수 없습니다.")
        allocatedQuantity -= quantity
    }
}
