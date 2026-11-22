package me.park.openrush.support.database

import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.TimeUnit

internal fun JdbcTemplate.awaitBlockedTransaction(blockerPid: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        val blocked = queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM pg_locks
                WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid))
            )
            """.trimIndent(),
            Boolean::class.java,
            blockerPid,
        )
        if (blocked == true) return
        Thread.sleep(20)
    }
    error("PostgreSQL 세션 ${blockerPid}의 락을 기다리는 트랜잭션을 찾지 못했습니다.")
}
