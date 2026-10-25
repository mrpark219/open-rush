package me.park.openrush.reservation.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
class SeatNumber(value: String) {
    @field:Column(name = "seat_number", nullable = false)
    var value: String = value
        protected set

    init {
        require(value.isNotBlank()) { "좌석 번호는 비어 있을 수 없습니다." }
    }

    override fun equals(other: Any?) = other is SeatNumber && value == other.value
    override fun hashCode() = value.hashCode()
}

/** 원 단위 금액. 초기 도메인은 KRW만 지원한다. */
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

enum class SeatStatus { AVAILABLE, HELD, RESERVED }
enum class ReservationStatus { HELD, CONFIRMED, EXPIRED, CANCELLED }
class ReservationRuleViolation(message: String) : IllegalStateException(message)

internal fun checkRule(condition: Boolean, message: String) {
    if (!condition) throw ReservationRuleViolation(message)
}
