package me.park.openrush.reservation.application.provided


data class PreparedReservationPayment(
    val transaction: PaymentPreparation,
    val approvalAllowed: Boolean,
)
