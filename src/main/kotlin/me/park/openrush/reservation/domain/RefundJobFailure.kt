package me.park.openrush.reservation.domain

enum class RefundJobFailure(val retryable: Boolean) {
    PG_UNAVAILABLE(true),
    PROCESSING_ERROR(true),
    PG_REJECTED(false),
    INVALID_PAYMENT_DATA(false),
}
