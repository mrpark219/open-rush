package me.park.openrush.support.database

import javax.sql.DataSource

/** 테스트 DB의 public 스키마에 추가되는 독립 테이블도 초기화 대상에 자동으로 포함한다. */
class DatabaseCleaner(private val dataSource: DataSource) {
    fun clean() {
        // DataSourceUtils를 거치지 않아 테스트 트랜잭션과 분리된 연결에서 초기화를 커밋한다.
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            val tableNames = connection.prepareStatement(
                """
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                ORDER BY tablename
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString("tablename"))
                    }
                }
            }
            if (tableNames.isEmpty()) return

            val qualifiedTableNames = tableNames.joinToString(", ") { tableName ->
                "\"public\".\"${tableName.replace("\"", "\"\"")}\""
            }
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE TABLE $qualifiedTableNames RESTART IDENTITY")
            }
        }
    }
}
