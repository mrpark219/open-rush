package me.park.openrush.support.database

import me.park.openrush.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 편의 어노테이션 없이도 공통 리스너가 데이터 생성 전에 DB를 초기화해야 한다.
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class DatabaseCleanerTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun createConcertAfterCleanup() {
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM concerts", Int::class.java))
        jdbcTemplate.update("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('테스트 공연', '', 4)")
    }

    @Test
    fun `외래 키 없는 테이블도 비우고 마이그레이션 이력은 보존한다`() {
        val migrationHistory = jdbcTemplate.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")
        assertTrue(migrationHistory.isNotEmpty())
        jdbcTemplate.execute("CREATE TABLE public.\"cleanup_test_independent\" (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY)")
        try {
            jdbcTemplate.update("INSERT INTO public.cleanup_test_independent DEFAULT VALUES")
            DatabaseCleaner(dataSource).clean()

            assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM cleanup_test_independent", Int::class.java))
            assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM concerts", Int::class.java))
            assertEquals(migrationHistory, jdbcTemplate.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
        } finally {
            jdbcTemplate.execute("DROP TABLE public.cleanup_test_independent")
        }
    }

    @Test
    fun `첫 번째 테스트 데이터는 정리 이후에 생성된다`() {
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM concerts", Int::class.java))
    }

    @Test
    fun `다음 테스트도 이전 데이터 없이 시작한다`() {
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM concerts", Int::class.java))
    }
}
