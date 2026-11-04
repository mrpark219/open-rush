package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.seat.PerformanceSeat
import org.springframework.data.jpa.repository.JpaRepository

interface SeatRepository : JpaRepository<PerformanceSeat, Long>, SeatRepositoryCustom
