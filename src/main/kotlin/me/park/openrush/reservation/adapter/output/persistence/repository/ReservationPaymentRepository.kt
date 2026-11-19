package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import org.springframework.data.jpa.repository.JpaRepository

interface ReservationPaymentRepository : JpaRepository<ReservationPaymentTransaction, Long>, ReservationPaymentRepositoryCustom
