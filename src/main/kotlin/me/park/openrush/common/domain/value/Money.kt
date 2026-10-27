package me.park.openrush.common.domain.value

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
class Money(amount: Long) {
    @field:Column(nullable = false)
    var amount: Long = amount
        protected set

    init {
        require(amount >= 0) { "금액은 음수일 수 없습니다." }
    }

    override fun equals(other: Any?) = other is Money && amount == other.amount
    override fun hashCode() = amount.hashCode()
}
