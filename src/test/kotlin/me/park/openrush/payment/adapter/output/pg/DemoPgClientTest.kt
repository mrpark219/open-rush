package me.park.openrush.payment.adapter.output.pg

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import feign.FeignException
import me.park.openrush.config.FeignConfiguration
import org.apache.hc.core5.http.ConnectionRequestTimeoutException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [DemoPgClientTest.TestApplication::class],
    properties = [
        "spring.cloud.openfeign.httpclient.max-connections=1",
        "spring.cloud.openfeign.httpclient.max-connections-per-route=1",
    ],
)
class DemoPgClientTest {
    @Autowired
    private lateinit var client: DemoPgClient

    @Autowired
    private lateinit var json: ObjectMapper

    @BeforeEach
    fun setUp() {
        requests.clear()
        respond = { error("테스트에서 PG 응답을 설정해야 합니다.") }
    }

    @Test
    fun `취소 요청은 결제 키와 멱등 키 및 취소 금액을 전달한다`() {
        respond = {
            it.reply(
                200,
                """{"paymentKey":"PK-1001","orderId":"ORDER-1001","currency":"KRW","totalAmount":15000,"balanceAmount":0,"status":"CANCELED","cancels":[{"transactionKey":"CANCEL-1","cancelAmount":15000,"cancelReason":"예약 확정 실패","cancelStatus":"DONE"}]}"""
            )
        }
        val response = client.cancel("PK-1001", "cancel-order-1001", DemoPgCancelRequest("예약 확정 실패", 15000))
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/payments/PK-1001/cancel", request.path)
        assertEquals("cancel-order-1001", request.idempotencyKey)
        assertEquals(15000L, json.readTree(request.body)["cancelAmount"].asLong())
        assertEquals("예약 확정 실패", json.readTree(request.body)["cancelReason"].asString())
        assertEquals("CANCEL-1", response.cancels.single().transactionKey)
    }

    @Test
    fun `승인과 주문 조회가 요청 규약을 지키고 같은 커넥션을 재사용한다`() {
        respond = { exchange -> exchange.reply(200, DONE_RESPONSE) }

        val approved = client.confirm("confirm-order-1001", approvalRequest)
        val found = client.findByOrder("ORDER-1001")

        assertEquals(DemoPgPaymentResponse("PK-1001", "ORDER-1001", "KRW", 15000, 15000, "DONE"), approved)
        assertEquals(approved, found)
        val (post, get) = requests
        assertEquals("POST", post.method)
        assertEquals("/v1/payments/confirm", post.path)
        assertEquals("confirm-order-1001", post.idempotencyKey)
        val body = json.readTree(post.body)
        assertEquals("PK-1001", body["paymentKey"].asString())
        assertEquals("ORDER-1001", body["orderId"].asString())
        assertEquals(15000L, body["amount"].asLong())
        assertEquals("GET", get.method)
        assertEquals("/v1/payments/orders/ORDER-1001", get.path)
        assertEquals(post.remotePort, get.remotePort)
        assertEquals(2, requests.size)
    }

    @Test
    fun `주문 미존재와 카드 거절을 HTTP 상태와 PG 오류 코드로 구분한다`() {
        respond = { exchange ->
            if (exchange.requestMethod == "GET") exchange.reply(404, """{"code":"NOT_FOUND_PAYMENT"}""")
            else exchange.reply(403, """{"code":"REJECT_CARD_PAYMENT"}""")
        }

        val missing = assertFailsWith<FeignException.NotFound> { client.findByOrder("ORDER-MISSING") }
        val rejected =
            assertFailsWith<FeignException.Forbidden> { client.confirm("confirm-order-1001", approvalRequest) }
        assertEquals("NOT_FOUND_PAYMENT", json.readTree(missing.contentUTF8())["code"].asString())
        assertEquals("REJECT_CARD_PAYMENT", json.readTree(rejected.contentUTF8())["code"].asString())
        assertEquals(2, requests.size)
    }

    @Test
    fun `PG가 Retry-After와 503을 반환해도 승인은 한 번만 호출한다`() {
        respond = { exchange ->
            exchange.responseHeaders.set("Retry-After", "0")
            exchange.reply(503, """{"code":"PROVIDER_ERROR"}""")
        }

        val failure = assertFailsWith<FeignException> { client.confirm("confirm-order-1001", approvalRequest) }
        assertEquals(503, failure.status())
        assertEquals("PROVIDER_ERROR", json.readTree(failure.contentUTF8())["code"].asString())
        assertEquals(1, requests.size)
    }

    @Test
    fun `승인이 커넥션을 점유하면 조회에는 풀 대기 제한을 적용하고 승인에는 읽기 제한을 적용한다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        respond = { exchange ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.reply(200, DONE_RESPONSE)
        }
        try {
            val approval = caller.submit<FeignException> {
                assertFailsWith { client.confirm("confirm-order-1001", approvalRequest) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val lookup = assertFailsWith<FeignException> { client.findByOrder("ORDER-1001") }
            assertTrue(lookup.cause is ConnectionRequestTimeoutException, lookup.toString())
            val expired = approval.get(5, TimeUnit.SECONDS)
            assertTrue(expired.cause is SocketTimeoutException, expired.toString())
            assertEquals(listOf("POST"), requests.map { it.method })
        } finally {
            release.countDown()
            caller.shutdownNow()
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class])
    @Import(FeignConfiguration::class)
    class TestApplication

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val body: String,
        val idempotencyKey: String?,
        val remotePort: Int,
    )

    companion object {
        private const val DONE_RESPONSE =
            """{"paymentKey":"PK-1001","orderId":"ORDER-1001","currency":"KRW","totalAmount":15000,"balanceAmount":15000,"status":"DONE","version":"2022-11-16"}"""
        private val approvalRequest = DemoPgConfirmRequest("PK-1001", "ORDER-1001", 15000)
        private val requests = CopyOnWriteArrayList<RecordedRequest>()
        private val serverExecutor = Executors.newCachedThreadPool()

        @Volatile
        private var respond: (HttpExchange) -> Unit = {}
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = serverExecutor
            createContext("/") { exchange ->
                exchange.use {
                    requests.add(
                        RecordedRequest(
                            it.requestMethod,
                            it.requestURI.path,
                            it.requestBody.bufferedReader().use { reader -> reader.readText() },
                            it.requestHeaders.getFirst("Idempotency-Key"),
                            it.remoteAddress.port,
                        ),
                    )
                    respond(it)
                }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun mockPgAddress(registry: DynamicPropertyRegistry) {
            registry.add("payment.demo-pg.base-url") { "http://127.0.0.1:${server.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            server.stop(0)
            serverExecutor.shutdownNow()
        }

        private fun HttpExchange.reply(status: Int, body: String) {
            val bytes = body.toByteArray()
            responseHeaders.set("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.write(bytes)
        }
    }
}
