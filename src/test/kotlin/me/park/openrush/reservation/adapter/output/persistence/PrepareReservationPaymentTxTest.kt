package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.reservation.application.provided.PaymentPreparation
import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessCommand
import me.park.openrush.reservation.application.provided.*
import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["reservation.expiration.enabled=false"])
class PrepareReservationPaymentTxTest {
    @Autowired
    private lateinit var preparePaymentTx: PrepareReservationPaymentTxUseCase
    @Autowired
    private lateinit var paymentTransactions: ReservationPaymentRepositoryPort
    @Autowired
    private lateinit var createReservation: CreateReservationUseCase
    @Autowired
    private lateinit var cancelHold: CancelReservationHoldUseCase
    @Autowired
    private lateinit var jdbc: JdbcTemplate
    @Autowired
    private lateinit var recordPayment: RecordPaymentSuccessUseCase
    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager
    @Autowired
    private lateinit var dataSource: DataSource
    private val attemptNumber = UUID.randomUUID()
    private var performanceId = 0L
    private var seatNumber = 0

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE concerts CASCADE")
        seatNumber = 0
        val concertId =
            id("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        performanceId = id(
            """
            INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES (?, '공연장', now() + interval '2 days', now() - interval '1 day', now() + interval '1 day')
            RETURNING id
            """.trimIndent(),
            concertId,
        )
    }

    @Test
    fun `선점한 예매 금액으로 거래를 저장하고 같은 시도에는 기존 주문과 키를 반환한다`() {
        val reservationId = hold(15000, 20000)

        val order = prepare(reservationId, 1L, attemptNumber)

        assertEquals(reservationId, order.reservationId)
        assertEquals(attemptNumber, order.attemptNumber)
        assertEquals("PK-$attemptNumber", order.requestedPaymentKey)
        assertEquals(35000L, order.amount)
        assertTrue(requireNotNull(order.pgOrderNumber).matches(Regex("[a-zA-Z0-9_-]{6,64}")))
        assertEquals("confirm-${order.pgOrderNumber}", order.approvalIdempotencyKey)
        assertEquals(order.id, paymentTransactions.findByAttempt(reservationId, attemptNumber)?.id)
        assertEquals(order.id, prepare(reservationId, 1L, attemptNumber).id)
        assertEquals(1, paymentTxCount())
        assertEquals(
            "HELD",
            jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, reservationId)
        )
    }

    @Test
    fun `같은 결제 시도가 동시에 들어오면 거래와 승인 멱등성 키는 하나만 생성한다`() {
        val reservationId = hold(15000)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = List(2) {
                executor.submit<PaymentPreparation> {
                    barrier.await(5, TimeUnit.SECONDS)
                    prepare(reservationId, 1L, attemptNumber)
                }
            }
            val results = requests.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results.first().id, results.last().id)
            assertEquals(1, paymentTxCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `한 예매의 다른 결제 시도는 각각의 거래와 PG 주문으로 저장한다`() {
        val reservationId = hold(15000)
        val secondAttemptNumber = UUID.randomUUID()

        val first = prepare(reservationId, 1L, attemptNumber)
        val second = prepare(reservationId, 1L, secondAttemptNumber)

        assertEquals(first.reservationId, second.reservationId)
        assertEquals(first.amount, second.amount)
        assertEquals(secondAttemptNumber, second.attemptNumber)
        assertNotEquals(first.pgOrderNumber, second.pgOrderNumber)
        assertNotEquals(first.approvalIdempotencyKey, second.approvalIdempotencyKey)
        assertEquals(first.id, paymentTransactions.findByAttempt(reservationId, attemptNumber)?.id)
        assertEquals(second.id, paymentTransactions.findByAttempt(reservationId, secondAttemptNumber)?.id)
        assertEquals(2, paymentTxCount())
    }

    @Test
    fun `승인과 부분 취소는 준비 행과 동일한 PG 주문 ID로 각각 누적할 수 있다`() {
        val reservationId = hold(15000)
        val preparation = prepare(reservationId, 1L, attemptNumber)
        val payment =
            RecordPaymentSuccessCommand(preparation.provider, "payment-1", reservationId, 15000, "KRW", preparation.pgOrderNumber)

        val approved = recordPayment.record(payment)
        assertEquals(approved, recordPayment.record(payment))
        TransactionTemplate(transactionManager).executeWithoutResult {
            val approval = requireNotNull(paymentTransactions.findApproved(payment.provider, payment.paymentId))
            assertEquals(attemptNumber, approval.attemptNumber)
            val first = approval.prepareCancellation(UUID.randomUUID(), 5000, "부분 취소")
            val second = approval.prepareCancellation(UUID.randomUUID(), 10000, "나머지 취소")
            paymentTransactions.save(first)
            paymentTransactions.save(first.canceled("cancel-1"))
            paymentTransactions.save(second)
            paymentTransactions.save(second.canceled("cancel-2"))
        }

        assertEquals(
            listOf("APPROVAL_PREPARED:15000", "APPROVED:15000", "CANCEL_PREPARED:5000", "CANCELED:5000", "CANCEL_PREPARED:10000", "CANCELED:10000"),
            jdbc.queryForList(
                "SELECT type || ':' || amount FROM reservation_payment_tx WHERE pg_order_number = ? ORDER BY id",
                String::class.java, preparation.pgOrderNumber,
            ),
        )
        assertEquals(preparation.id, paymentTransactions.findByAttempt(reservationId, attemptNumber)?.id)
    }

    @Test
    fun `취소 준비 커밋 후 호출이 실패해도 같은 번호로 준비 정보를 복구하고 완료를 기록한다`() {
        val reservationId = hold(15000)
        recordPayment.record(RecordPaymentSuccessCommand("test-provider", "payment-1", reservationId, 15000, "KRW"))
        val cancellationNumber = UUID.randomUUID()
        val tx = TransactionTemplate(transactionManager)
        val preparedId = tx.execute {
            val approval = requireNotNull(paymentTransactions.findApproved("test-provider", "payment-1"))
            val prepared = approval.prepareCancellation(cancellationNumber, 5000, "부분 취소")
            paymentTransactions.save(prepared)
            requireNotNull(prepared.id)
        }

        // PG 호출 실패는 커밋한 준비 이력을 지우지 않는다.
        assertFailsWith<IllegalStateException> { error("PG 호출 실패") }
        val recovered = requireNotNull(paymentTransactions.findCancellationPreparation(reservationId, cancellationNumber))
        assertEquals(preparedId, recovered.id)
        assertEquals(5000L, recovered.amount)
        assertEquals("부분 취소", recovered.cancelReason)
        assertEquals("cancel-$cancellationNumber", recovered.cancellationIdempotencyKey)
        assertEquals(null, paymentTransactions.findCanceled(reservationId, cancellationNumber))

        tx.executeWithoutResult { paymentTransactions.save(recovered.canceled("cancel-1")) }

        val completed = requireNotNull(paymentTransactions.findCanceled(reservationId, cancellationNumber))
        assertEquals(cancellationNumber, completed.cancellationNumber)
        assertEquals(recovered.cancellationIdempotencyKey, completed.cancellationIdempotencyKey)
        assertEquals(recovered.amount, completed.amount)
        assertEquals(recovered.cancelReason, completed.cancelReason)
        assertEquals("cancel-1", completed.cancellationId)
        assertEquals(preparedId, paymentTransactions.findCancellationPreparation(reservationId, cancellationNumber)?.id)

        assertFailsWith<org.springframework.dao.DataIntegrityViolationException> {
            tx.executeWithoutResult {
                val approval = requireNotNull(paymentTransactions.findApproved("test-provider", "payment-1"))
                paymentTransactions.save(approval.prepareCancellation(cancellationNumber, 5000, "부분 취소"))
            }
        }
        assertFailsWith<org.springframework.dao.DataIntegrityViolationException> {
            tx.executeWithoutResult { paymentTransactions.save(recovered.canceled("different-cancel-id")) }
        }
    }

    @Test
    fun `같은 PG 결제 ID가 두 예매에 전달되면 한 예매만 확정한다`() {
        val first = hold(15000)
        val second = hold(15000)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).map { reservationId ->
                executor.submit<Boolean> {
                    barrier.await(5, TimeUnit.SECONDS)
                    try {
                        recordPayment.record(
                            RecordPaymentSuccessCommand(
                                "demo-pg",
                                "conflicting-payment",
                                reservationId,
                                15000,
                                "KRW"
                            )
                        )
                        true
                    } catch (_: PaymentRecordConflictException) {
                        false
                    }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            assertEquals(1, paymentTxCount())
            assertEquals(1, id("SELECT count(*) FROM reservations WHERE status = 'CONFIRMED'").toInt())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `기존 결제 결과는 예매와 연결 및 기록 시각을 유지하며 승인 행으로 이전한다`() {
        val schema = "payment_tx_migration_test"
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).target("4").load().migrate()
            jdbc.execute(
                """
                INSERT INTO $schema.concerts(id, title, description, max_tickets_per_user)
                VALUES (1, '공연', '', 4);
                INSERT INTO $schema.performances(id, concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
                VALUES (1, 1, '공연장', now() + interval '2 days', now() - interval '1 day', now() + interval '1 day');
                INSERT INTO $schema.reservations(id, performance_id, user_id, held_at, expires_at, status)
                VALUES (42, 1, 1, now(), now() + interval '5 minutes', 'HELD');
                INSERT INTO $schema.payment_records(provider, payment_id, reservation_id, amount, currency, status, refund_reason, recorded_at)
                VALUES ('demo-pg', 'legacy-payment', 42, 15000, 'KRW', 'REFUND_REQUIRED', 'PAYMENT_MISMATCH', '2026-11-01T01:00:00Z');
                """.trimIndent(),
            )
            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate()

            assertEquals(
                1,
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM $schema.reservation_payment_tx
                    WHERE reservation_id = 42 AND type = 'APPROVED' AND provider = 'demo-pg' AND payment_id = 'legacy-payment'
                        AND amount = 15000 AND currency = 'KRW' AND status = 'REFUND_REQUIRED' AND refund_reason = 'PAYMENT_MISMATCH'
                        AND recorded_at = '2026-11-01T01:00:00Z' AND attempt_number IS NULL AND pg_order_number IS NULL
                    """.trimIndent(),
                    Int::class.java,
                ),
            )
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @Test
    fun `식별자 컬럼 이름 변경은 기존 승인 준비와 멱등성을 보존한다`() {
        val schema = "payment_number_migration_test"
        val number = UUID.randomUUID()
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).target("6").load().migrate()
            jdbc.execute(
                """
                INSERT INTO $schema.concerts(id, title, description, max_tickets_per_user)
                VALUES (1, '공연', '', 4);
                INSERT INTO $schema.performances(id, concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
                VALUES (1, 1, '공연장', now() + interval '2 days', now() - interval '1 day', now() + interval '1 day');
                INSERT INTO $schema.reservations(id, performance_id, user_id, held_at, expires_at, status)
                VALUES (42, 1, 1, now(), now() + interval '5 minutes', 'HELD');
                """.trimIndent(),
            )
            jdbc.update(
                """
                INSERT INTO $schema.reservation_payment_tx
                    (id, reservation_id, type, provider, amount, attempt_id, pg_order_id,
                     approval_idempotency_key, requested_payment_key)
                VALUES (100, 42, 'APPROVAL_PREPARED', 'demo-pg', 15000, ?, 'OR-existing', 'confirm-existing', 'PK-existing')
                """.trimIndent(),
                number,
            )
            jdbc.update(
                """
                INSERT INTO $schema.reservation_payment_tx
                    (id, reservation_id, type, provider, amount, payment_id, cancellation_id, currency)
                VALUES (102, 42, 'CANCELED', 'demo-pg', 5000, 'legacy-payment', 'legacy-cancel', 'KRW')
                """.trimIndent(),
            )

            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate()

            val row = jdbc.queryForMap("SELECT * FROM $schema.reservation_payment_tx WHERE id = 100")
            assertEquals(number, row["attempt_number"])
            assertEquals("OR-existing", row["pg_order_number"])
            assertEquals("confirm-existing", row["approval_idempotency_key"])
            assertEquals("PK-existing", row["requested_payment_key"])
            val legacy = jdbc.queryForMap("SELECT * FROM $schema.reservation_payment_tx WHERE id = 102")
            assertEquals("CANCELED", legacy["type"])
            assertEquals("legacy-cancel", legacy["cancellation_id"])
            assertEquals(null, legacy["cancellation_number"])
            assertFailsWith<org.springframework.dao.DuplicateKeyException> {
                jdbc.update(
                    """
                    INSERT INTO $schema.reservation_payment_tx
                        (id, reservation_id, type, provider, amount, attempt_number, pg_order_number,
                         approval_idempotency_key, requested_payment_key)
                    VALUES (101, 42, 'APPROVAL_PREPARED', 'demo-pg', 15000, ?, 'OR-other', 'confirm-other', 'PK-other')
                    """.trimIndent(),
                    number,
                )
            }
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @Test
    fun `타인의 예매와 존재하지 않는 예매는 결제 주문을 생성할 수 없다`() {
        val reservationId = hold(15000)
        assertFailsWith<ReservationRuleViolationException> { prepare(reservationId, 2L, attemptNumber) }
        assertFailsWith<ReservationRuleViolationException> { prepare(Long.MAX_VALUE, 1L, attemptNumber) }
        assertEquals(0, paymentTxCount())
    }

    @Test
    fun `만료 시각이 지난 선점과 취소된 선점은 주문을 생성할 수 없다`() {
        val expiredHold = hold(15000)
        val cancelledHold = hold(20000)
        jdbc.update(
            """
            UPDATE reservations SET held_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute'
            WHERE id = ?
            """.trimIndent(),
            expiredHold,
        )
        cancelHold.cancelHold(CancelReservationHoldCommand(1L, cancelledHold))

        assertFailsWith<ReservationRuleViolationException> { prepare(expiredHold, 1L, attemptNumber) }
        assertFailsWith<ReservationRuleViolationException> { prepare(cancelledHold, 1L, attemptNumber) }
        assertEquals(0, paymentTxCount())
    }

    @Test
    fun `금액이 0원인 예매는 결제 주문을 생성할 수 없다`() {
        val reservationId = hold(0)
        assertFailsWith<ReservationRuleViolationException> { prepare(reservationId, 1L, attemptNumber) }
        assertEquals(0, paymentTxCount())
    }

    private fun prepare(reservationId: Long, userId: Long, attemptNumber: UUID) = preparePaymentTx.prepare(
        ApproveReservationPaymentCommand(reservationId, userId, attemptNumber, "PK-$attemptNumber"),
    ).transaction

    private fun hold(vararg prices: Long): Long {
        val seatIds = prices.map { price ->
            id(
                """
                INSERT INTO performance_seats(performance_id, seat_number, price, status)
                VALUES (?, ?, ?, 'AVAILABLE') RETURNING id
                """.trimIndent(),
                performanceId, "A-${++seatNumber}", price,
            )
        }
        return createReservation.create(CreateReservationCommand(1L, performanceId, seatIds)).reservationId
    }

    private fun paymentTxCount(): Int =
        jdbc.queryForObject("SELECT count(*) FROM reservation_payment_tx", Int::class.java)!!

    private fun id(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!
}
