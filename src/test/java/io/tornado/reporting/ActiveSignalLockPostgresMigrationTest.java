package io.tornado.reporting;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ActiveSignalLockPostgresMigrationTest {
    private static final String COMMON_MIGRATIONS = "classpath:db/migration";
    private static final String POSTGRES_MIGRATIONS = "classpath:db/vendor/postgresql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void migrateToVersion18() {
        flyway(null).clean();
        flyway("18").migrate();
    }

    @Test
    void cleanVersion18UpgradeCreatesThePartialUniqueIndex() throws Exception {
        insertLock(1, 101, 3600, "CLOSED_TP");
        insertLock(1, 102, 3600, "CLOSED_SL");
        insertLock(1, 103, 3600, "CLOSED_TIMEOUT");
        insertLock(1, 104, 3600, "OPEN");

        flyway(null).migrate();

        assertThat(indexDefinition()).contains(
                "UNIQUE INDEX uk_active_signal_lock_open_coin_horizon",
                "(coin_id, horizon_seconds)",
                "WHERE ((status)::text = 'OPEN'::text)");
        assertThatThrownBy(() -> insertLock(1, 105, 3600, "OPEN"))
                .hasMessageContaining("uk_active_signal_lock_open_coin_horizon");
        assertThat(lockCount()).isEqualTo(4);
        assertThat(migrationGuardTriggerExists()).isFalse();
    }

    @Test
    void dirtyVersion18UpgradeFailsBeforeV19WithoutDeletingEitherLock() throws Exception {
        insertLock(7, 201, 14400, "OPEN");
        insertLock(7, 202, 14400, "OPEN");

        assertThatThrownBy(() -> flyway(null).migrate())
                .hasStackTraceContaining("Cannot enforce active-signal OPEN uniqueness")
                .hasStackTraceContaining("coin_id=7 horizon=14400 open_rows=2")
                .hasStackTraceContaining("resolve the correct ActiveSignalLock states");

        assertThat(lockCount()).isEqualTo(2);
        assertThat(currentVersion()).isEqualTo("18");
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(COMMON_MIGRATIONS, POSTGRES_MIGRATIONS)
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private void insertLock(long coinId, long simulationId, long horizon, String status)
            throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try {
                statement.executeUpdate("""
                        INSERT INTO active_signal_locks
                          (coin_id, horizon_seconds, best_method_mix_id,
                           mix_trade_simulation_id, opened_at, entry_price,
                           expected_close_at, status, closed_at, close_price,
                           created_at, updated_at)
                        VALUES (
                          %d, %d, NULL, %d,
                          TIMESTAMPTZ '2026-08-23 10:00:00+00', 100,
                          TIMESTAMPTZ '2026-08-23 11:00:00+00', '%s',
                          %s, NULL,
                          TIMESTAMPTZ '2026-08-23 10:00:00+00',
                          TIMESTAMPTZ '2026-08-23 10:00:00+00'
                        )
                        """.formatted(
                        coinId,
                        horizon,
                        simulationId,
                        status,
                        "OPEN".equals(status)
                                ? "NULL"
                                : "TIMESTAMPTZ '2026-08-23 10:30:00+00'"));
            } finally {
                statement.execute("SET session_replication_role = origin");
            }
        }
    }

    private String indexDefinition() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT indexdef
                     FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND indexname = 'uk_active_signal_lock_open_coin_horizon'
                     """)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private long lockCount() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM active_signal_locks")) {
            result.next();
            return result.getLong(1);
        }
    }

    private String currentVersion() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT version
                     FROM flyway_schema_history
                     WHERE success
                     ORDER BY installed_rank DESC
                     LIMIT 1
                     """)) {
            result.next();
            return result.getString(1);
        }
    }

    private boolean migrationGuardTriggerExists() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT EXISTS (
                       SELECT 1
                       FROM pg_trigger
                       WHERE tgname = 'tornado_guard_active_signal_open_scope'
                         AND NOT tgisinternal
                     )
                     """)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
