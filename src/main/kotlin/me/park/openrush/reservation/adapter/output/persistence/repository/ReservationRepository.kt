package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.Reservation
import org.springframework.data.jpa.repository.JpaRepository

interface ReservationRepository : JpaRepository<Reservation, Long>
