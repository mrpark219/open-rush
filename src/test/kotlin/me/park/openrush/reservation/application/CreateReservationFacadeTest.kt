package me.park.openrush.reservation.application

import jakarta.persistence.PessimisticLockException
import jakarta.persistence.OptimisticLockException
import me.park.openrush.common.exception.ConcurrencyException
import me.park.openrush.reservation.application.exception.PurchaseQuotaCreationConflict
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationResult
import me.park.openrush.reservation.domain.ReservationStatus
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CreateReservationFacadeTest {
    @Test
    fun `낙관적 락 충돌은 최대 세 번 재시도하고 성공 결과를 반환한다`() {
        val conflict = OptimisticLockingFailureException("버전 충돌")
        `when`(service.create(command)).thenThrow(conflict, conflict, conflict).thenReturn(result)

        assertSame(result, facade.create(command))
        verify(service, times(4)).create(command)
    }

    @Test
    fun `세 번 재시도한 뒤에도 충돌하면 공통 동시성 예외로 반환한다`() {
        val conflict = OptimisticLockException("버전 충돌")
        `when`(service.create(command)).thenThrow(conflict)

        val exception = assertFailsWith<ConcurrencyException> { facade.create(command) }

        assertSame(conflict, exception.cause)
        verify(service, times(4)).create(command)
    }

    @Test
    fun `구매 한도 생성 충돌도 전체 재시도 한도에 포함한다`() {
        val creationConflict = PurchaseQuotaCreationConflict(RuntimeException())
        val versionConflict = OptimisticLockingFailureException("버전 충돌")
        `when`(service.create(command)).thenThrow(creationConflict, versionConflict, versionConflict, versionConflict)

        assertFailsWith<ConcurrencyException> { facade.create(command) }
        verify(service, times(4)).create(command)
    }

    @Test
    fun `재시도 중 정책 거절이 발생하면 즉시 중단한다`() {
        val conflict = OptimisticLockingFailureException("버전 충돌")
        val rejection = ReservationRuleViolation("이미 선점된 좌석")
        `when`(service.create(command)).thenThrow(conflict, rejection)

        assertSame(rejection, assertFailsWith<ReservationRuleViolation> { facade.create(command) })
        verify(service, times(2)).create(command)
    }

    @Test
    fun `구매 한도 생성 충돌만 한 번 재시도한다`() {
        val conflict = PurchaseQuotaCreationConflict(RuntimeException())
        `when`(service.create(command)).thenThrow(conflict).thenReturn(result)

        assertSame(result, facade.create(command))
        verify(service, times(2)).create(command)
    }

    @Test
    fun `두 번째 구매 한도 생성 충돌은 재시도하지 않고 동시성 예외로 반환한다`() {
        val conflict = PurchaseQuotaCreationConflict(RuntimeException())
        `when`(service.create(command)).thenThrow(conflict)

        val exception = assertFailsWith<ConcurrencyException> { facade.create(command) }

        assertSame(conflict, exception.cause)
        verify(service, times(2)).create(command)
    }

    @Test
    fun `다른 무결성 오류는 재시도하거나 동시성 오류로 변환하지 않는다`() {
        val integrityViolation = DataIntegrityViolationException("다른 제약 조건 위반")
        `when`(service.create(command)).thenThrow(integrityViolation)

        assertSame(integrityViolation, assertFailsWith<DataIntegrityViolationException> { facade.create(command) })
        verify(service, times(1)).create(command)
    }

    @Test
    fun `정책 거절은 재시도하지 않는다`() {
        val rejection = ReservationRuleViolation("구매 한도 초과")
        `when`(service.create(command)).thenThrow(rejection)

        assertSame(rejection, assertFailsWith<ReservationRuleViolation> { facade.create(command) })
        verify(service, times(1)).create(command)
    }

    @Test
    fun `트랜잭션 호출에서 전파된 잠금 오류는 재시도 없이 변환한다`() {
        val lockFailure = PessimisticLockException("잠금 오류")
        `when`(service.create(command)).thenThrow(lockFailure)

        val exception = assertFailsWith<ConcurrencyException> { facade.create(command) }

        assertSame(lockFailure, exception.cause)
        verify(service, times(1)).create(command)
    }

    private val service = mock(CreateReservationService::class.java)
    private val facade = CreateReservationFacade(service)
    private val command = CreateReservationCommand(1, 1, listOf(1))
    private val result = CreateReservationResult(
        reservationId = 1,
        performanceId = 1,
        status = ReservationStatus.HELD,
        heldAt = Instant.parse("2026-10-30T00:00:00Z"),
        expiresAt = Instant.parse("2026-10-30T00:05:00Z"),
        quantity = 1,
        totalAmount = 50_000,
        seatIds = listOf(1),
    )
}
