package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.reservation.application.exception.PaymentRecordConflict
import me.park.openrush.reservation.application.port.input.CancelReservationHoldCommand
import me.park.openrush.reservation.application.port.input.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import me.park.openrush.reservation.application.port.input.ApproveReservationPaymentCommand
import me.park.openrush.reservation.application.port.input.PrepareReservationPaymentTxUseCase
import me.park.openrush.reservation.application.port.input.RecordPaymentSuccessUseCase
import me.park.openrush.reservation.application.port.output.ReservationPaymentTxPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.domain.payment.ReservationPaymentTx
import me.park.openrush.reservation.domain.payment.VerifiedPayment
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
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
    @Autowired private lateinit var preparePaymentTx: PrepareReservationPaymentTxUseCase
    @Autowired private lateinit var paymentTransactions: ReservationPaymentTxPort
    @Autowired private lateinit var createReservation: CreateReservationUseCase
    @Autowired private lateinit var cancelHold: CancelReservationHoldUseCase
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var recordPayment: RecordPaymentSuccessUseCase
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var dataSource: DataSource
    private val attemptId = UUID.randomUUID()
    private var performanceId = 0L
    private var seatNumber = 0

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE concerts CASCADE")
        seatNumber = 0
        val concertId = id("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        performanceId = id(
            """
            INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES (?, '공연장', now() + interval '2 days', now() - interval '1 day', now() + interval '1 day')
            RETURNING id
            """.trimIndent(), concertId,
        )
    }

    @Test
    fun `선점한 예매 금액으로 거래를 저장하고 같은 시도에는 기존 주문과 키를 반환한다`() {
        val reservationId = hold(15000, 20000)

        val order = prepare(reservationId, 1L, attemptId)

        assertEquals(reservationId, order.reservationId)
        assertEquals(attemptId, order.attemptId)
        assertEquals("PK-$attemptId", order.requestedPaymentKey)
        assertEquals(35000L, order.amount)
        assertTrue(requireNotNull(order.pgOrderId).matches(Regex("[a-zA-Z0-9_-]{6,64}")))
        assertEquals("confirm-${order.pgOrderId}", order.approvalIdempotencyKey)
        assertEquals(order.id, paymentTransactions.findByAttempt(reservationId, attemptId)?.id)
        assertEquals(order.id, prepare(reservationId, 1L, attemptId).id)
        assertEquals(1, paymentTxCount())
        assertEquals("HELD", jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, reservationId))
    }

    @Test
    fun `같은 결제 시도가 동시에 들어오면 거래와 승인 멱등성 키는 하나만 생성한다`() {
        val reservationId = hold(15000)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = List(2) {
                executor.submit<ReservationPaymentTx> {
                    barrier.await(5, TimeUnit.SECONDS)
                    prepare(reservationId, 1L, attemptId)
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
        val secondAttemptId = UUID.randomUUID()

        val first = prepare(reservationId, 1L, attemptId)
        val second = prepare(reservationId, 1L, secondAttemptId)

        assertEquals(first.reservationId, second.reservationId)
        assertEquals(first.amount, second.amount)
        assertEquals(secondAttemptId, second.attemptId)
        assertNotEquals(first.pgOrderId, second.pgOrderId)
        assertNotEquals(first.approvalIdempotencyKey, second.approvalIdempotencyKey)
        assertEquals(first.id, paymentTransactions.findByAttempt(reservationId, attemptId)?.id)
        assertEquals(second.id, paymentTransactions.findByAttempt(reservationId, secondAttemptId)?.id)
        assertEquals(2, paymentTxCount())
    }

    @Test
    fun `승인과 부분 취소는 준비 행과 동일한 PG 주문 ID로 각각 누적할 수 있다`() {
        val reservationId = hold(15000)
        val preparation = prepare(reservationId, 1L, attemptId)
        val payment = VerifiedPayment(preparation.provider, "payment-1", reservationId, 15000, "KRW", preparation.pgOrderId)

        val approved = recordPayment.record(payment)
        assertEquals(approved, recordPayment.record(payment))
        TransactionTemplate(transactionManager).executeWithoutResult {
            val approval = requireNotNull(paymentTransactions.findApproved(payment.provider, payment.paymentId))
            assertEquals(attemptId, approval.attemptId)
            paymentTransactions.save(approval.canceled("cancel-1", 5000))
            paymentTransactions.save(approval.canceled("cancel-2", 10000))
        }

        assertEquals(
            listOf("APPROVAL_PREPARED:15000", "APPROVED:15000", "CANCELED:5000", "CANCELED:10000"),
            jdbc.queryForList(
                "SELECT type || ':' || amount FROM reservation_payment_tx WHERE pg_order_id = ? ORDER BY id",
                String::class.java, preparation.pgOrderId,
            ),
        )
        assertEquals(preparation.id, paymentTransactions.findByAttempt(reservationId, attemptId)?.id)
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
                        recordPayment.record(VerifiedPayment("demo-pg", "conflicting-payment", reservationId, 15000, "KRW"))
                        true
                    } catch (_: PaymentRecordConflict) {
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
                        AND recorded_at = '2026-11-01T01:00:00Z' AND attempt_id IS NULL AND pg_order_id IS NULL
                    """.trimIndent(), Int::class.java,
                ),
            )
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @Test
    fun `타인의 예매와 존재하지 않는 예매는 결제 주문을 생성할 수 없다`() {
        val reservationId = hold(15000)
        assertFailsWith<ReservationRuleViolation> { prepare(reservationId, 2L, attemptId) }
        assertFailsWith<ReservationRuleViolation> { prepare(Long.MAX_VALUE, 1L, attemptId) }
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
            """.trimIndent(), expiredHold,
        )
        cancelHold.cancelHold(CancelReservationHoldCommand(1L, cancelledHold))

        assertFailsWith<ReservationRuleViolation> { prepare(expiredHold, 1L, attemptId) }
        assertFailsWith<ReservationRuleViolation> { prepare(cancelledHold, 1L, attemptId) }
        assertEquals(0, paymentTxCount())
    }

    @Test
    fun `금액이 0원인 예매는 결제 주문을 생성할 수 없다`() {
        val reservationId = hold(0)
        assertFailsWith<ReservationRuleViolation> { prepare(reservationId, 1L, attemptId) }
        assertEquals(0, paymentTxCount())
    }

    private fun prepare(reservationId: Long, userId: Long, attemptId: UUID) = preparePaymentTx.prepare(
        ApproveReservationPaymentCommand(reservationId, userId, attemptId, "PK-$attemptId"),
    ).transaction

    private fun hold(vararg prices: Long): Long {
        val seatIds = prices.map { price ->
            id(
                """
                INSERT INTO performance_seats(performance_id, seat_number, price, status)
                VALUES (?, ?, ?, 'AVAILABLE') RETURNING id
                """.trimIndent(), performanceId, "A-${++seatNumber}", price,
            )
        }
        return createReservation.create(CreateReservationCommand(1L, performanceId, seatIds)).reservationId
    }

    private fun paymentTxCount(): Int = jdbc.queryForObject("SELECT count(*) FROM reservation_payment_tx", Int::class.java)!!
    private fun id(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!
}
