package me.park.openrush.reservation.adapter.input.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jakarta.servlet.ServletException
import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.support.web.GlobalExceptionHandler
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["reservation.expiration.enabled=false"])
class ApproveReservationPaymentTest {
    @Autowired
    private lateinit var controller: ReservationPaymentController

    @Autowired
    private lateinit var advice: GlobalExceptionHandler

    @Autowired
    private lateinit var reservations: CreateReservationUseCase

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager
    private lateinit var mvc: MockMvc
    private var reservationId = 0L
    private lateinit var attemptNumber: UUID
    private val orderId: String
        get() = jdbc.queryForObject(
            "SELECT pg_order_number FROM reservation_payment_tx WHERE type = 'APPROVAL_PREPARED'",
            String::class.java
        )!!

    @BeforeEach
    fun setUp() {
        requests.clear()
        respond = { it.reply(500, "{}") }
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build()
        jdbc.execute("TRUNCATE concerts CASCADE")
        val concertId =
            id("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        val performanceId = id(
            """
            INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES (?, '공연장', now() + interval '2 days', now() - interval '1 day', now() + interval '1 day') RETURNING id
        """.trimIndent(), concertId
        )
        val seatId = id(
            "INSERT INTO performance_seats(performance_id, seat_number, price, status) VALUES (?, 'A-1', 15000, 'AVAILABLE') RETURNING id",
            performanceId
        )
        reservationId = reservations.create(CreateReservationCommand(1L, performanceId, listOf(seatId))).reservationId
        attemptNumber = UUID.randomUUID()
    }

    @Test
    fun `준비를 커밋하고 잠금을 해제한 뒤 PG 승인과 예매 확정을 처리한다`() {
        var committedAndUnlocked = false
        normalResponses(beforeApproval = {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.queryForObject(
                    "SELECT id FROM reservations WHERE id = ? FOR UPDATE NOWAIT",
                    Long::class.java,
                    reservationId
                )
                assertEquals(1, count("APPROVAL_PREPARED"))
                committedAndUnlocked = true
            }
        })
        confirm().andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESERVATION_CONFIRMED"))
            .andExpect(jsonPath("$.paymentKey").value("PK-TEST"))
        assertTrue(committedAndUnlocked)
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(1, count("APPROVED"))
        assertEquals(listOf("GET", "POST"), requests.map { it.first })
        assertEquals(
            "PK-TEST",
            jdbc.queryForObject(
                "SELECT requested_payment_key FROM reservation_payment_tx WHERE type = 'APPROVAL_PREPARED'",
                String::class.java
            )
        )
    }

    @Test
    fun `완료된 같은 요청은 PG 재호출 없이 기존 결과를 반환한다`() {
        normalResponses()
        confirm().andExpect(status().isOk)
        val requestCount = requests.size
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESERVATION_CONFIRMED"))
        assertEquals(requestCount, requests.size)
        assertEquals(1, count("APPROVED"))
        confirm("PK-CHANGED").andExpect(status().isConflict)
        assertEquals(requestCount, requests.size)
        confirm(userId = 2).andExpect(status().isConflict)
        assertEquals(requestCount, requests.size)
    }

    @Test
    fun `승인 응답이 유실되어도 주문 조회로 승인 결과를 저장한다`() {
        val approved = AtomicBoolean(false)
        respond = { exchange ->
            if (exchange.requestMethod == "POST") {
                approved.set(true)
                exchange.close()
            } else if (!approved.get()) exchange.reply(404, "{}") else exchange.reply(200, done())
        }
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESERVATION_CONFIRMED"))
        assertEquals(listOf("GET", "POST", "GET"), requests.map { it.first })
        assertEquals(1, count("APPROVED"))
    }

    @Test
    fun `승인 읽기 타임아웃도 주문 조회로 복구하며 자동 승인 재시도는 하지 않는다`() {
        val release = CountDownLatch(1)
        val approved = CountDownLatch(1)
        respond = { exchange ->
            if (exchange.requestMethod == "POST") {
                approved.countDown()
                release.await(5, TimeUnit.SECONDS)
                exchange.close()
            } else if (approved.count > 0) exchange.reply(404, "{}") else exchange.reply(200, done())
        }
        try {
            confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESERVATION_CONFIRMED"))
            assertEquals(listOf("GET", "POST", "GET"), requests.map { it.first })
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `같은 시도의 동시 승인도 동일한 멱등 키를 사용하고 결과는 한 행만 저장한다`() {
        val callers = Executors.newFixedThreadPool(2)
        val posts = CountDownLatch(2)
        normalResponses(beforeApproval = {
            posts.countDown()
            assertTrue(posts.await(5, TimeUnit.SECONDS))
        })
        try {
            val results = List(2) { callers.submit<Int> { confirm().andReturn().response.status } }
            assertEquals(listOf(200, 200), results.map { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, count("APPROVED"))
            assertEquals(1, count("APPROVAL_PREPARED"))
            assertEquals(1, requests.filter { it.first == "POST" }.distinct().size)
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `승인과 후속 조회가 실패하면 확인 대기하고 재요청에서 같은 주문과 키를 사용한다`() {
        val getCount = AtomicInteger()
        respond = { exchange ->
            if (exchange.requestMethod == "GET" && getCount.incrementAndGet() == 1) exchange.reply(404, "{}")
            else exchange.reply(503, "{}")
        }
        confirm().andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("PENDING"))
        assertEquals("HELD", reservationStatus())
        assertEquals(0, count("APPROVED"))
        val firstApproval = requests.single { it.first == "POST" }
        normalResponses()
        confirm().andExpect(status().isOk)
        assertEquals(firstApproval, requests.last { it.first == "POST" })
        assertEquals(1, count("APPROVAL_PREPARED"))
    }

    @Test
    fun `최초 조회 장애가 있으면 중복 승인을 시도하지 않고 확인 대기한다`() {
        respond = { it.reply(503, "{}") }
        confirm().andExpect(status().isAccepted)
        assertEquals(listOf("GET"), requests.map { it.first })
    }

    @Test
    fun `PG 승인 후 DB 저장이 롤백되어도 재요청의 주문 조회로 복구한다`() {
        val approved = AtomicBoolean(false)
        respond = {
            if (it.requestMethod == "POST") {
                approved.set(true); it.reply(200, done())
            } else if (approved.get()) it.reply(200, done()) else it.reply(404, "{}")
        }
        jdbc.execute(
            """
            CREATE FUNCTION reject_test_approval() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.type = 'APPROVED' THEN
                    RAISE EXCEPTION 'forced failure' USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END $$
        """.trimIndent()
        )
        jdbc.execute("CREATE TRIGGER reject_test_approval BEFORE INSERT ON reservation_payment_tx FOR EACH ROW EXECUTE FUNCTION reject_test_approval()")
        try {
            assertFailsWith<ServletException> { confirm() }
            assertEquals("HELD", reservationStatus())
            assertEquals(1, count("APPROVAL_PREPARED"))
            assertEquals(0, count("APPROVED"))
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_approval ON reservation_payment_tx")
            jdbc.execute("DROP FUNCTION reject_test_approval()")
        }
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESERVATION_CONFIRMED"))
        assertEquals(listOf("GET", "POST", "GET"), requests.map { it.first })
        assertEquals(1, count("APPROVED"))
    }

    @Test
    fun `PG 카드 거절은 422이고 예매 확정과 승인 이력이 없다`() {
        respond = {
            if (it.requestMethod == "GET") it.reply(404, "{}") else it.reply(
                403,
                """{"code":"REJECT_CARD_PAYMENT"}"""
            )
        }
        confirm().andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PAYMENT_REJECTED"))
        assertEquals("HELD", reservationStatus())
        assertEquals(0, count("APPROVED"))
    }

    @Test
    fun `PG 응답 주문이나 결제 키 또는 상태가 다르면 확정하지 않는다`() {
        listOf<() -> String>(
            { done(order = "OTHER-ORDER") },
            { done(key = "OTHER-KEY") },
            { done(state = "CANCELED") }).forEach { response ->
            val posted = AtomicBoolean(false)
            respond = {
                if (it.requestMethod == "POST") {
                    posted.set(true); it.reply(200, response())
                } else if (!posted.get()) it.reply(404, "{}") else it.reply(200, response())
            }
            confirm().andExpect(status().isAccepted)
            assertEquals(0, count("APPROVED"))
        }
        assertEquals("HELD", reservationStatus())
    }

    @Test
    fun `PG 승인 금액과 통화 불일치는 승인 사실과 환불 필요를 저장한다`() {
        normalResponses(response = { done(amount = 10000, currency = "USD") })
        confirm().andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
            .andExpect(jsonPath("$.refundReason").value("PAYMENT_MISMATCH"))
        assertEquals(1, count("APPROVED"))
        assertEquals("HELD", reservationStatus())
    }

    @Test
    fun `결제 도중 예매가 만료되면 승인 사실과 환불 필요를 저장한다`() {
        normalResponses(beforeApproval = { expire() })
        confirm().andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
            .andExpect(jsonPath("$.refundReason").value("RESERVATION_UNAVAILABLE"))
        assertEquals(1, count("APPROVED"))
    }

    @Test
    fun `확인 대기 후 만료되어도 기존 승인 조회와 기록은 가능하다`() {
        respond = { it.reply(503, "{}") }
        confirm().andExpect(status().isAccepted)
        expire()
        respond = { it.reply(200, done()) }
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        assertEquals(listOf("GET", "GET", "POST"), requests.map { it.first })
    }

    @Test
    fun `확인 대기 후 만료된 주문이 PG에 없으면 새 승인은 하지 않는다`() {
        respond = { it.reply(503, "{}") }
        confirm().andExpect(status().isAccepted)
        expire()
        respond = { it.reply(404, "{}") }
        confirm().andExpect(status().isAccepted)
        assertEquals(listOf("GET", "GET"), requests.map { it.first })
        assertEquals(0, count("APPROVED"))
    }

    @Test
    fun `만료된 예매의 새로운 시도와 타인의 예매는 PG를 호출하지 않는다`() {
        confirm(userId = 2).andExpect(status().isConflict)
        expire()
        confirm().andExpect(status().isConflict)
        assertTrue(requests.isEmpty())
        assertEquals(0, count("APPROVAL_PREPARED"))
    }

    @Test
    fun `잘못된 입력은 PG 호출 전에 거절한다`() {
        listOf(
            """{"userId":0,"attemptNumber":"$attemptNumber","paymentKey":"PK"}""",
            """{"userId":1,"attemptNumber":"invalid","paymentKey":"PK"}""",
            """{"userId":1,"attemptNumber":"$attemptNumber","paymentKey":" "}""",
            """{"userId":1,"attemptNumber":"$attemptNumber","paymentKey":"${"X".repeat(201)}"}""",
            "{}"
        ).forEach { body ->
            mvc.perform(
                post("/reservations/$reservationId/confirm").contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
                .andExpect(status().isBadRequest)
        }
        assertTrue(requests.isEmpty())
    }

    private fun normalResponses(beforeApproval: () -> Unit = {}, response: () -> String = { done() }) {
        respond = { exchange ->
            if (exchange.requestMethod == "GET") exchange.reply(404, "{}")
            else {
                beforeApproval()
                exchange.reply(200, response())
            }
        }
    }

    private fun confirm(key: String = "PK-TEST", userId: Long = 1) = mvc.perform(
        post("/reservations/$reservationId/confirm").contentType(MediaType.APPLICATION_JSON)
            .content("""{"userId":$userId,"attemptNumber":"$attemptNumber","paymentKey":"$key"}"""),
    )

    private fun done(
        order: String = orderId,
        key: String = "PK-TEST",
        state: String = "DONE",
        amount: Long = 15000,
        currency: String = "KRW"
    ) =
        """{"paymentKey":"$key","orderId":"$order","currency":"$currency","totalAmount":$amount,"balanceAmount":$amount,"status":"$state"}"""

    private fun expire() {
        jdbc.update(
            "UPDATE reservations SET held_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute' WHERE id = ?",
            reservationId
        )
    }

    private fun count(type: String) =
        jdbc.queryForObject("SELECT count(*) FROM reservation_payment_tx WHERE type = ?", Int::class.java, type)

    private fun reservationStatus() =
        jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, reservationId)

    private fun id(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    companion object {
        private val requests = CopyOnWriteArrayList<Pair<String, String>>()

        @Volatile
        private var respond: (HttpExchange) -> Unit = { it.reply(500, "{}") }
        private val pgExecutor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/payments") { exchange ->
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                requests.add(
                    exchange.requestMethod to (exchange.requestURI.path + "|" + exchange.requestHeaders.getFirst(
                        "Idempotency-Key"
                    ) + "|" + body)
                )
                respond(exchange)
            }
            this.executor = pgExecutor
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("payment.demo-pg.base-url") { "http://127.0.0.1:${server.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            server.stop(0); pgExecutor.shutdownNow()
        }

        private fun HttpExchange.reply(status: Int, body: String) {
            val bytes = body.toByteArray()
            responseHeaders.set("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
