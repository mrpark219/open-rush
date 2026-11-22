package me.park.openrush.reservation.adapter.output.persistence

import jakarta.persistence.EntityManager
import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.reservation.application.required.ConfigureExpirationTransactionPort
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DatabaseIntegrationTest(
    properties = ["reservation.expiration.lock-timeout-ms=200",
        "spring.datasource.hikari.maximum-pool-size=1"]
)
class ExpirationTransactionPersistenceAdapterTest {
    @Autowired
    private lateinit var configureExpirationTransactionPort: ConfigureExpirationTransactionPort
    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired
    private lateinit var entityManager: EntityManager
    @Autowired
    private lateinit var jdbcConnectionDetails: JdbcConnectionDetails

    @Test
    fun `커밋 후 같은 풀 연결을 재사용해도 잠금 대기 설정이 남지 않는다`() {
        val original = lockTimeout()
        val connectionId = connectionId()
        TransactionTemplate(transactionManager).executeWithoutResult {
            configureExpirationTransactionPort.configure()
            assertEquals("200ms", lockTimeout())
            assertEquals(connectionId, connectionId())
        }
        assertEquals(connectionId, connectionId())
        assertEquals(original, lockTimeout())
    }

    @Test
    fun `실제 잠금 시간 초과로 롤백된 뒤에도 같은 연결에 설정이 남지 않는다`() {
        val original = lockTimeout()
        val connectionId = connectionId()
        // 풀 밖의 연결로 잠금을 잡아, 풀 크기가 1이어도 실제 시간 초과와 연결 재사용을 검증한다.
        DriverManager.getConnection(jdbcConnectionDetails.jdbcUrl, jdbcConnectionDetails.username, jdbcConnectionDetails.password).use { holder ->
            holder.createStatement().use { it.execute("SELECT pg_advisory_lock(934007)") }
            val failure = assertFailsWith<RuntimeException> {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    configureExpirationTransactionPort.configure()
                    assertEquals("200ms", lockTimeout())
                    entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(934007)").singleResult
                }
            }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<SQLException>().any { it.sqlState == "55P03" })
        }
        assertEquals(connectionId, connectionId())
        assertEquals(original, lockTimeout())
    }

    @Test
    fun `활성 트랜잭션 없이 설정을 적용할 수 없다`() {
        val original = lockTimeout()
        assertFailsWith<IllegalTransactionStateException> { configureExpirationTransactionPort.configure() }
        assertEquals(original, lockTimeout())
    }

    private fun lockTimeout() = jdbcTemplate.queryForObject("SHOW lock_timeout", String::class.java)
    private fun connectionId() = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Int::class.java)
}
