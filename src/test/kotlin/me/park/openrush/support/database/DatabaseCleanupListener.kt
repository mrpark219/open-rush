package me.park.openrush.support.database

import org.springframework.test.context.TestContext
import org.springframework.test.context.support.AbstractTestExecutionListener
import javax.sql.DataSource

class DatabaseCleanupListener : AbstractTestExecutionListener() {
    // Spring의 테스트 트랜잭션 시작(4000) 및 @BeforeEach보다 먼저 실행한다.
    override fun getOrder(): Int = 3500

    override fun beforeTestMethod(testContext: TestContext) {
        val dataSource = testContext.applicationContext.getBeanProvider(DataSource::class.java)
            .getIfAvailable() ?: return
        DatabaseCleaner(dataSource).clean()
    }
}
