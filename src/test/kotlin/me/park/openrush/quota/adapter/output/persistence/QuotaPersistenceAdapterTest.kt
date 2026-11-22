package me.park.openrush.quota.adapter.output.persistence

import me.park.openrush.quota.adapter.output.persistence.repository.PurchaseQuotaRepository
import me.park.openrush.quota.application.exception.PurchaseQuotaCreationConflictException
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class QuotaPersistenceAdapterTest {
    private val purchaseQuotaRepository = mock(PurchaseQuotaRepository::class.java)
    private val quotaPersistenceAdapter = QuotaPersistenceAdapter(purchaseQuotaRepository)

    @Test
    fun `Repository가 변환한 한도 행 중복 오류를 생성 충돌로 전달한다`() {
        val failure = constraintViolation("uk_user_concert_quota")
        `when`(purchaseQuotaRepository.findQuotaId(1, 1)).thenReturn(null)
        `when`(purchaseQuotaRepository.createQuota(1, 1)).thenThrow(failure)

        val conflict = assertFailsWith<PurchaseQuotaCreationConflictException> { quotaPersistenceAdapter.createQuota(1, 1) }

        assertSame(failure, conflict.cause)
    }

    @Test
    fun `다른 제약 위반은 생성 충돌로 바꾸지 않는다`() {
        val failure = constraintViolation("fk_concert_purchase_quota_concert")
        `when`(purchaseQuotaRepository.findQuotaId(1, 1)).thenReturn(null)
        `when`(purchaseQuotaRepository.createQuota(1, 1)).thenThrow(failure)

        assertSame(failure, assertFailsWith<DataIntegrityViolationException> { quotaPersistenceAdapter.createQuota(1, 1) })
    }

    private fun constraintViolation(constraintName: String): DataIntegrityViolationException {
        val cause = mock(ConstraintViolationException::class.java)
        `when`(cause.constraintName).thenReturn(constraintName)
        return DataIntegrityViolationException("제약 조건 위반", cause)
    }
}
