package me.park.openrush.reservation.application.port.input

fun interface PrepareReservationPaymentTxUseCase {
    fun prepare(command: ApproveReservationPaymentCommand): PreparedReservationPayment
}
