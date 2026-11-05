package me.park.openrush.reservation.adapter.output.persistence.repository

import java.time.Instant

interface PurchaseQuotaRepositoryCustom {
    fun decreaseIfAllocated(userId: Long, concertId: Long, quantity: Int, modifiedAt: Instant): Long

    fun findQuotaId(userId: Long, concertId: Long): Long?

    fun createQuota(userId: Long, concertId: Long): Long

    fun getMaxTicketsPerUser(concertId: Long): Int

    fun increaseIfWithinLimit(
        quotaId: Long,
        quantity: Int,
        maxTicketsPerUser: Int,
        modifiedAt: Instant,
    ): Long
}
