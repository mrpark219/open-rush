package me.park.openrush.support.database

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * DB 경합 테스트에 필요한 연결을 모두 확보한 뒤 반환한다.
 * 유휴 연결이 없는 풀의 연결 생성 속도가 트랜잭션 내부 Barrier의 검증에 섞이지 않게 한다.
 */
fun DataSource.prepareConcurrentConnections(count: Int) {
    val maximumPoolSize = unwrap(HikariDataSource::class.java).maximumPoolSize
    require(count in 1..maximumPoolSize) {
        "동시에 필요한 연결 수는 1 이상이며 연결 풀 최대 크기($maximumPoolSize)를 넘을 수 없습니다: $count"
    }

    fun acquireConnections(remaining: Int) {
        if (remaining == 0) return
        // 모든 연결을 동시에 확보할 때까지 유지하고, 일부 확보에 실패해도 역순으로 전부 반환한다.
        connection.use { acquireConnections(remaining - 1) }
    }

    acquireConnections(count)
}
