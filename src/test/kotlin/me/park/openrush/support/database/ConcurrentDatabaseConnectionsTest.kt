package me.park.openrush.support.database

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ConcurrentDatabaseConnectionsTest {
    private val dataSource = mock(DataSource::class.java)
    private val connectionPool = mock(HikariDataSource::class.java)

    init {
        `when`(dataSource.unwrap(HikariDataSource::class.java)).thenReturn(connectionPool)
        `when`(connectionPool.maximumPoolSize).thenReturn(2)
    }

    @Test
    fun `필요한 연결을 모두 확보한 다음 반환한다`() {
        val firstConnection = mock(Connection::class.java)
        val secondConnection = mock(Connection::class.java)
        `when`(dataSource.connection).thenReturn(firstConnection, secondConnection)

        dataSource.prepareConcurrentConnections(2)

        inOrder(dataSource, firstConnection, secondConnection).apply {
            verify(dataSource).unwrap(HikariDataSource::class.java)
            verify(dataSource, times(2)).connection
            verify(secondConnection).close()
            verify(firstConnection).close()
        }
    }

    @Test
    fun `일부 연결 확보에 실패하면 이미 확보한 연결도 반환한다`() {
        val firstConnection = mock(Connection::class.java)
        val connectionFailure = SQLException("연결 확보 실패")
        `when`(dataSource.connection).thenReturn(firstConnection).thenThrow(connectionFailure)

        val failure = assertFailsWith<SQLException> { dataSource.prepareConcurrentConnections(2) }

        assertSame(connectionFailure, failure)
        inOrder(dataSource, firstConnection).apply {
            verify(dataSource).unwrap(HikariDataSource::class.java)
            verify(dataSource, times(2)).connection
            verify(firstConnection).close()
        }
    }

    @Test
    fun `연결 풀 크기를 초과하거나 양수가 아닌 요청은 연결을 확보하기 전에 거절한다`() {
        listOf(0, -1, 3).forEach { count ->
            assertFailsWith<IllegalArgumentException> { dataSource.prepareConcurrentConnections(count) }
        }
        verify(dataSource, never()).connection
    }
}
