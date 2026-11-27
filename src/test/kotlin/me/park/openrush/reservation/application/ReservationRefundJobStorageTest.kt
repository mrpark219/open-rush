package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.provided.ApproveReservationPaymentCommand
import me.park.openrush.reservation.application.provided.CancelReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.reservation.application.provided.PrepareReservationPaymentTxUseCase
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessCommand
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessUseCase
import me.park.openrush.reservation.application.provided.RefundReservationPaymentCommand
import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.domain.PaymentStatus
import me.park.openrush.reservation.domain.RefundJobFailure
import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.database.prepareConcurrentConnections
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DatabaseIntegrationTest
class ReservationRefundJobStorageTest {
    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var prepareReservationPaymentTxUseCase: PrepareReservationPaymentTxUseCase

    @Autowired
    private lateinit var recordPaymentSuccessUseCase: RecordPaymentSuccessUseCase

    @Autowired
    private lateinit var cancelReservationTransactions: CancelReservationTransactions

    @Autowired
    private lateinit var reservationPaymentRefundTransactions: ReservationPaymentRefundTransactions

    @Autowired
    private lateinit var reservationRefundJobTransactions: ReservationRefundJobTransactions

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var reservationPaymentRepository: ReservationPaymentRepositoryPort

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private var reservationId = 0L
    private lateinit var attemptNumber: UUID
    private lateinit var paymentCommand: RecordPaymentSuccessCommand

    @BeforeEach
    fun setUp() {
        val concertId = ConcertFixture(jdbcTemplate).createConcert()
        val performanceId = ConcertFixture(jdbcTemplate).createPerformance(concertId)
        val seatId = InventoryFixture(jdbcTemplate).createSeat(performanceId)
        reservationId = createReservationUseCase.create(
            CreateReservationCommand(1, performanceId, listOf(seatId)),
        ).reservationId
        attemptNumber = UUID.randomUUID()
        val preparedPayment = prepareReservationPaymentTxUseCase.prepare(
            ApproveReservationPaymentCommand(reservationId, 1, attemptNumber, "PK-REFUND-JOB"),
        )
        paymentCommand = RecordPaymentSuccessCommand(
            provider = "demo-pg",
            paymentId = "PK-REFUND-JOB",
            reservationId = reservationId,
            amount = 15000,
            currency = "KRW",
            pgOrderNumber = preparedPayment.transaction.pgOrderNumber,
        )
    }

    @Test
    fun `예약 취소 커밋 직후 PG 호출 전에도 승인 이력에 연결된 환불 작업이 남는다`() {
        recordPaymentSuccessUseCase.record(paymentCommand)
        assertEquals(0, jobCount())

        cancelReservationTransactions.cancel(cancelCommand())

        assertEquals("CANCELLED", reservationStatus())
        assertEquals("AVAILABLE", jdbcTemplate.queryForObject("SELECT status FROM performance_seats", String::class.java))
        assertEquals(1, jobCount())
        assertEquals("PENDING", jobStatus())
        assertEquals(
            jdbcTemplate.queryForObject("SELECT id FROM reservation_payment_tx WHERE type = 'APPROVED'", Long::class.java),
            jdbcTemplate.queryForObject("SELECT approved_payment_transaction_id FROM reservation_refund_jobs", Long::class.java),
        )
        assertEquals(1, transactionCount("CANCEL_PREPARED"))
        assertEquals(0, transactionCount("CANCELED"))
    }

    @Test
    fun `환불 필요 승인 저장 시 취소 준비나 PG 환불 전에 작업을 함께 저장한다`() {
        expireReservation()

        val firstResult = recordPaymentSuccessUseCase.record(paymentCommand)
        val repeatedResult = recordPaymentSuccessUseCase.record(paymentCommand)

        assertEquals(PaymentStatus.REFUND_REQUIRED, firstResult.status)
        assertEquals(firstResult, repeatedResult)
        assertEquals(1, jobCount())
        assertEquals("PENDING", jobStatus())
        assertEquals(1, transactionCount("APPROVED"))
        assertEquals(0, transactionCount("CANCEL_PREPARED"))
    }

    @Test
    fun `서로 다른 요청이 동시에 취소해도 환불 작업은 승인당 하나만 저장한다`() {
        recordPaymentSuccessUseCase.record(paymentCommand)
        checkNotNull(jdbcTemplate.dataSource).prepareConcurrentConnections(2)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = List(2) {
                executor.submit<UUID?> {
                    barrier.await(5, TimeUnit.SECONDS)
                    cancelReservationTransactions.cancel(cancelCommand())
                }
            }
            assertEquals(listOf<UUID?>(attemptNumber, attemptNumber), requests.map { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, jobCount())
            assertEquals(1, transactionCount("CANCEL_PREPARED"))
            assertEquals("PENDING", jobStatus())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `좌석 반환에 실패하면 취소 준비와 환불 작업을 함께 롤백한다`() {
        recordPaymentSuccessUseCase.record(paymentCommand)
        jdbcTemplate.update("UPDATE performance_seats SET status = 'AVAILABLE', reservation_id = NULL")

        assertFailsWith<IllegalStateException> {
            cancelReservationTransactions.cancel(cancelCommand())
        }

        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(0, jobCount())
        assertEquals(0, transactionCount("CANCEL_PREPARED"))
        assertEquals(1, jdbcTemplate.queryForObject("SELECT allocated_quantity FROM concert_purchase_quotas", Int::class.java))
    }

    @Test
    fun `환불 작업 저장 실패 시 예약 취소와 자원 반환도 커밋되지 않는다`() {
        recordPaymentSuccessUseCase.record(paymentCommand)

        withJobWriteFailure("INSERT") {
            assertFailsWith<DataIntegrityViolationException> {
                cancelReservationTransactions.cancel(cancelCommand())
            }
        }

        assertEquals("CONFIRMED", reservationStatus())
        assertEquals("RESERVED", jdbcTemplate.queryForObject("SELECT status FROM performance_seats", String::class.java))
        assertEquals(0, jobCount())
        assertEquals(0, transactionCount("CANCEL_PREPARED"))
    }

    @Test
    fun `환불 작업 저장 실패 시 환불 필요 승인 이력도 함께 롤백한다`() {
        expireReservation()

        withJobWriteFailure("INSERT") {
            assertFailsWith<DataIntegrityViolationException> {
                recordPaymentSuccessUseCase.record(paymentCommand)
            }
        }

        assertEquals(0, transactionCount("APPROVED"))
        assertEquals(0, jobCount())
        assertEquals(PaymentStatus.REFUND_REQUIRED, recordPaymentSuccessUseCase.record(paymentCommand).status)
        assertEquals(1, jobCount())
    }

    @Test
    fun `환불 완료 이력과 작업 완료를 함께 저장하고 중복 결과는 완료 시각을 유지한다`() {
        val cancellationPreparation = prepareCancellation().cancellationPreparation
        val claim = assertNotNull(reservationRefundJobTransactions.claimNext())

        reservationPaymentRefundTransactions.record(claim, cancellationPreparation, "CANCEL-REFUND-JOB")
        val completedAt = completedAt()
        assertNotNull(completedAt)
        assertEquals("COMPLETED", jobStatus())

        assertNull(reservationPaymentRefundTransactions.record(claim, cancellationPreparation, "CANCEL-REFUND-JOB"))

        assertEquals(1, jobCount())
        assertEquals(1, transactionCount("CANCELED"))
        assertEquals(completedAt, completedAt())
    }

    @Test
    fun `작업 완료 저장이 실패하면 취소 완료 이력도 롤백하고 대기 작업을 유지한다`() {
        val cancellationPreparation = prepareCancellation().cancellationPreparation
        val claim = assertNotNull(reservationRefundJobTransactions.claimNext())

        withJobWriteFailure("UPDATE") {
            assertFailsWith<DataIntegrityViolationException> {
                reservationPaymentRefundTransactions.record(claim, cancellationPreparation, "CANCEL-REFUND-JOB")
            }
        }

        assertEquals("CANCELLED", reservationStatus())
        assertEquals(0, transactionCount("CANCELED"))
        assertEquals("PENDING", jobStatus())

        reservationPaymentRefundTransactions.record(claim, cancellationPreparation, "CANCEL-REFUND-JOB")
        assertEquals(1, transactionCount("CANCELED"))
        assertEquals("COMPLETED", jobStatus())
    }

    @Test
    fun `점유가 만료되면 다시 가져오고 이전 점유의 완료와 실패 기록은 무시한다`() {
        val cancellationPreparation = prepareCancellation().cancellationPreparation
        val firstClaim = assertNotNull(reservationRefundJobTransactions.claimNext())
        assertNull(reservationRefundJobTransactions.claimNext())
        jdbcTemplate.update("UPDATE reservation_refund_jobs SET lease_expires_at = now() - interval '1 second'")

        val recoveredClaim = assertNotNull(reservationRefundJobTransactions.claimNext())
        assertEquals(firstClaim.jobId, recoveredClaim.jobId)
        assertTrue(firstClaim.token != recoveredClaim.token)
        reservationRefundJobTransactions.fail(firstClaim, RefundJobFailure.PG_REJECTED)
        assertNull(reservationPaymentRefundTransactions.record(firstClaim, cancellationPreparation, "STALE"))
        assertEquals("PENDING", jobStatus())
        assertEquals(0, transactionCount("CANCELED"))

        assertNotNull(reservationPaymentRefundTransactions.record(recoveredClaim, cancellationPreparation, "CANCEL-REFUND-JOB"))
        assertEquals("COMPLETED", jobStatus())
        assertEquals(1, transactionCount("CANCELED"))
    }

    @Test
    fun `점유 쿼리는 잠긴 첫 작업을 기다리지 않고 다음 작업을 가져온다`() {
        expireReservation()
        recordPaymentSuccessUseCase.record(paymentCommand)
        recordPaymentSuccessUseCase.record(paymentCommand.copy(paymentId = "PK-NEXT-REFUND-JOB", pgOrderNumber = null))
        val jobIds = jdbcTemplate.query("SELECT id FROM reservation_refund_jobs ORDER BY id") { resultSet, _ ->
            resultSet.getLong("id")
        }
        val (firstJobId, secondJobId) = jobIds

        transactionTemplate.executeWithoutResult {
            val lockedJob = assertNotNull(reservationPaymentRepository.lockRefundJob(firstJobId))

            assertNull(reservationRefundJobTransactions.claimPayment(lockedJob.approvedPaymentTransactionId))
            assertEquals(secondJobId, reservationRefundJobTransactions.claimNext()?.jobId)
        }

        assertEquals(firstJobId, reservationRefundJobTransactions.claimNext()?.jobId)
    }

    private fun prepareCancellation(): PreparedReservationRefund {
        recordPaymentSuccessUseCase.record(paymentCommand)
        cancelReservationTransactions.cancel(cancelCommand())

        return reservationPaymentRefundTransactions.prepare(
            RefundReservationPaymentCommand(reservationId, 1, attemptNumber),
        )
    }

    private fun expireReservation() {
        jdbcTemplate.update(
            "UPDATE reservations SET held_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute' WHERE id = ?",
            reservationId,
        )
    }

    private fun cancelCommand() = CancelReservationCommand(reservationId, 1, "사용자 요청")

    private fun jobCount() = jdbcTemplate.queryForObject("SELECT count(*) FROM reservation_refund_jobs", Int::class.java)

    private fun jobStatus() = jdbcTemplate.queryForObject("SELECT status FROM reservation_refund_jobs", String::class.java)

    private fun completedAt() = jdbcTemplate.queryForObject("SELECT completed_at FROM reservation_refund_jobs", Instant::class.java)

    private fun reservationStatus() = jdbcTemplate.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, reservationId)

    private fun transactionCount(type: String) = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM reservation_payment_tx WHERE type = ?", Int::class.java, type,
    )

    private fun withJobWriteFailure(event: String, action: () -> Unit) {
        jdbcTemplate.execute("""
            CREATE FUNCTION reject_refund_job_storage() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION 'forced refund job write failure' USING ERRCODE = '23514';
            END $$
        """.trimIndent())
        jdbcTemplate.execute("CREATE TRIGGER reject_refund_job_storage BEFORE $event ON reservation_refund_jobs FOR EACH ROW EXECUTE FUNCTION reject_refund_job_storage()")
        try {
            action()
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_refund_job_storage ON reservation_refund_jobs")
            jdbcTemplate.execute("DROP FUNCTION reject_refund_job_storage()")
        }
    }
}
