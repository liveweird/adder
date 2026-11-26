package ch.nokillswit

import java.util.UUID
import org.flywaydb.core.Flyway

/** The PostgreSQL fixture for tests that assert database-wide budgets or deliberately change a global guard. */
internal class IsolatedTestDatabase(private val name: String) {
    val jdbcUrl: String get() = PostgresTestSupport.jdbcUrlFor(name)
    val r2dbcUrl: String get() = PostgresTestSupport.r2dbcUrl.substringBeforeLast('/') + "/" + name

    fun connect() = PostgresTestSupport.connect(name)
}

/** A migrated database on this fork's Testcontainers server. The shared suite database keeps its own rows and guards. */
internal fun <T> withIsolatedTestDatabase(test: (IsolatedTestDatabase) -> T): T {
    val name = "test_" + UUID.randomUUID().toString().replace("-", "")
    val database = IsolatedTestDatabase(name)
    executeDatabaseStatement("CREATE DATABASE $name")
    try {
        Flyway.configure()
            .dataSource(database.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return test(database)
    } finally {
        executeDatabaseStatement("DROP DATABASE $name WITH (FORCE)")
    }
}

private fun executeDatabaseStatement(sql: String) {
    PostgresTestSupport.connect().use { connection -> connection.createStatement().use { it.execute(sql) } }
}
