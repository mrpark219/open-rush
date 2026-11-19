package me.park.openrush.inventory.adapter.output.persistence.repository

import me.park.openrush.inventory.domain.PerformanceSeat
import org.springframework.data.jpa.repository.JpaRepository

interface SeatRepository : JpaRepository<PerformanceSeat, Long>, SeatRepositoryCustom
