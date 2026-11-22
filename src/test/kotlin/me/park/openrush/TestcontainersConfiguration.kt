package me.park.openrush

import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    fun jdbcConnectionDetails(): JdbcConnectionDetails = object : JdbcConnectionDetails {
        override fun getJdbcUrl(): String = WorkerPostgres.container.jdbcUrl
        override fun getUsername(): String = WorkerPostgres.container.username
        override fun getPassword(): String = WorkerPostgres.container.password
    }

    // 마이그레이션은 애플리케이션 연결 풀과 분리한다. 풀 크기 1인 테스트에서도
    // Flyway의 스키마 이력 잠금과 마이그레이션에 필요한 연결을 함께 열 수 있어야 한다.
    @Bean
    fun flywayConnectionDetails(): FlywayConnectionDetails = object : FlywayConnectionDetails {
        override fun getJdbcUrl(): String = WorkerPostgres.container.jdbcUrl
        override fun getUsername(): String = WorkerPostgres.container.username
        override fun getPassword(): String = WorkerPostgres.container.password
    }
}

/**
 * Gradle 테스트 프로세스마다 하나의 DB를 공유한다. 컨테이너를 Spring 빈으로 등록하지 않아
 * 개별 ApplicationContext 종료 시 다른 테스트가 사용하는 DB까지 종료되지 않게 한다.
 * 프로세스가 끝나면 Testcontainers의 Ryuk이 컨테이너를 정리한다.
 */
private object WorkerPostgres {
    val container: PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18")).apply {
        start()
    }
}
