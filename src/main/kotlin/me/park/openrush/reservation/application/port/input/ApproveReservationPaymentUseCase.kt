package me.park.openrush.reservation.application.port.input

fun interface ApproveReservationPaymentUseCase {
    fun approve(command: ApproveReservationPaymentCommand): ReservationPaymentResult
}
