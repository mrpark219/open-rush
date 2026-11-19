package me.park.openrush.concert.adapter.output.persistence.repository

import me.park.openrush.concert.domain.Performance
import org.springframework.data.jpa.repository.JpaRepository

interface PerformanceRepository : JpaRepository<Performance, Long>, PerformanceRepositoryCustom
