package me.park.openrush.reservation.domain.exception

class ReservationRuleViolation(message: String) : IllegalStateException(message)

internal fun checkRule(condition: Boolean, message: String) {
    if (!condition) throw ReservationRuleViolation(message)
}
