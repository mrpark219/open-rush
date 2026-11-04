package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.ConcertPurchaseQuota
import org.springframework.data.jpa.repository.JpaRepository

interface PurchaseQuotaRepository : JpaRepository<ConcertPurchaseQuota, Long>, PurchaseQuotaRepositoryCustom
