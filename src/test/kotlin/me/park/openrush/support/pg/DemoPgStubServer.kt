package me.park.openrush.support.pg

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 실제 HTTP 요청과 커넥션 재사용을 검증하는 테스트 전용 PG 서버. */
class DemoPgStubServer : AutoCloseable {
    val requests = CopyOnWriteArrayList<RecordedPgRequest>()

    @Volatile
    var respond: (HttpExchange) -> Unit = { it.reply(500, "{}") }

    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@DemoPgStubServer.executor
        createContext("/") { exchange ->
            exchange.use {
                requests.add(
                    RecordedPgRequest(
                        method = it.requestMethod,
                        path = it.requestURI.path,
                        body = it.requestBody.bufferedReader().use { reader -> reader.readText() },
                        idempotencyKey = it.requestHeaders.getFirst("Idempotency-Key"),
                        remotePort = it.remoteAddress.port,
                    ),
                )
                respond(it)
            }
        }
        start()
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    fun reset() {
        requests.clear()
        respond = { it.reply(500, "{}") }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "PG 테스트 서버 스레드가 종료되지 않았습니다." }
    }
}
