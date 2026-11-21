package me.park.openrush.reservation.application.provided

data class CancelReservationCommand(
    val reservationId: Long,
    val userId: Long,
    val cancelReason: String,
) {
    init {
        require(reservationId > 0 && userId > 0)
        require(cancelReason.isNotBlank() && cancelReason.length <= 255)
    }
}
