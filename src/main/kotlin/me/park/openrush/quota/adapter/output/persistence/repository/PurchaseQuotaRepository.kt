package me.park.openrush.quota.adapter.output.persistence.repository

import me.park.openrush.quota.domain.ConcertPurchaseQuota
import org.springframework.data.jpa.repository.JpaRepository

interface PurchaseQuotaRepository : JpaRepository<ConcertPurchaseQuota, Long>, PurchaseQuotaRepositoryCustom
