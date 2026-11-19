package me.park.openrush.reservation.domain.exception

class ReservationRuleViolationException(message: String) : IllegalStateException(message)

internal fun checkRule(condition: Boolean, message: String) {
    if (!condition) throw ReservationRuleViolationException(message)
}
