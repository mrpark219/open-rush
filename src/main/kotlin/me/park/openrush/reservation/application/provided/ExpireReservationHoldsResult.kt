package me.park.openrush.reservation.application.provided

data class ExpireReservationHoldsResult(val expired: Int, val skipped: Int, val failed: Int)
