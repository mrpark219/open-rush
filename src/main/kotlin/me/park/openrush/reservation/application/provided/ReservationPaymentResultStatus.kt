package me.park.openrush.reservation.application.provided

/** 결제 승인과 후속 환불을 종합한 유스케이스 처리 결과다. */
enum class ReservationPaymentResultStatus {
    PENDING,
    RESERVATION_CONFIRMED,
    REFUND_REQUIRED,
    REFUNDED,
}
