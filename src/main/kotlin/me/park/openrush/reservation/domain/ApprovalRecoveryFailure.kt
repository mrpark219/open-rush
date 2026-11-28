package me.park.openrush.reservation.domain

enum class ApprovalRecoveryFailure(val retryable: Boolean) {
    PG_UNAVAILABLE(true),
    RECORD_FAILED(true),
    PAYMENT_CONFLICT(false),
    INVALID_PREPARATION(false),
}
