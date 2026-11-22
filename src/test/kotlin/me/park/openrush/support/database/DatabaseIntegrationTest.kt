package me.park.openrush.support.database

import me.park.openrush.TestcontainersConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AliasFor
import org.springframework.test.context.ActiveProfiles

/** DB 통합 테스트에 Spring Boot, test 프로필과 PostgreSQL 실행 환경을 제공한다. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
annotation class DatabaseIntegrationTest(
    @get:AliasFor(annotation = SpringBootTest::class, attribute = "properties")
    val properties: Array<String> = [],
)
