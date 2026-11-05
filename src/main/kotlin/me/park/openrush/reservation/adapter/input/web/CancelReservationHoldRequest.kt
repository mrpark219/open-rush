package me.park.openrush.reservation.adapter.input.web

import jakarta.validation.constraints.Positive

data class CancelReservationHoldRequest(@field:Positive val userId: Long)
