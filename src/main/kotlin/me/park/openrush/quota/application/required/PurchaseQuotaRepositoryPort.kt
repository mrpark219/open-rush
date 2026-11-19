package me.park.openrush.quota.application.required

import java.time.Instant

interface PurchaseQuotaRepositoryPort {
    fun findQuotaId(userId: Long, concertId: Long): Long?
    fun createQuota(userId: Long, concertId: Long): Long
    fun increaseIfWithinLimit(quotaId: Long, quantity: Int, maxTicketsPerUser: Int, modifiedAt: Instant): Long
    fun decreaseIfAllocated(userId: Long, concertId: Long, quantity: Int, modifiedAt: Instant): Long
}
