package me.park.openrush.quota.adapter.output.persistence.repository

import java.time.Instant

interface PurchaseQuotaRepositoryCustom {
    fun decreaseIfAllocated(userId: Long, concertId: Long, quantity: Int, modifiedAt: Instant): Long

    fun findQuotaId(userId: Long, concertId: Long): Long?

    fun createQuota(userId: Long, concertId: Long): Long


    fun increaseIfWithinLimit(
        quotaId: Long,
        quantity: Int,
        maxTicketsPerUser: Int,
        modifiedAt: Instant,
    ): Long
}
