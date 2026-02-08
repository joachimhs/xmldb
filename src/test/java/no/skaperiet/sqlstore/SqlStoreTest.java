package no.skaperiet.sqlstore;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlStoreTest {

    private static final String JDBC_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

    @TempDir
    static Path tempDir;

    static Path queryFile;

    @BeforeAll
    static void setupDatabase() throws Exception {
        queryFile = tempDir.resolve("queries.sql");
        Files.writeString(queryFile, """
                -- :name getAllUsers
                SELECT * FROM users

                -- :name getUserById
                SELECT * FROM users WHERE id = {id}

                -- :name getUserByStatus
                SELECT * FROM users WHERE status = {status}

                -- :name getUserByIdAndStatus
                SELECT * FROM users WHERE id = {id} AND status = {status}

                -- :name insertUser
                INSERT INTO users (name, email, status, score) VALUES ({name}, {email}, {status}, {score})

                -- :name updateUserStatus
                UPDATE users SET status = {status} WHERE id = {id}

                -- :name deleteUser
                DELETE FROM users WHERE id = {id}

                -- :name getUserCount
                SELECT COUNT(*) AS user_count FROM users

                -- :name insertUserWithTimestamp
                INSERT INTO users (name, email, status, score, created_at) VALUES ({name}, {email}, {status}, {score}, {createdAt})

                -- :name getUsersAboveScore
                SELECT * FROM users WHERE score > {minScore}

                -- :name insertUserBoolActive
                INSERT INTO users (name, email, status, score, active) VALUES ({name}, {email}, {status}, {score}, {active})
                """);

        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE users (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        email VARCHAR(200),
                        status VARCHAR(50),
                        score DOUBLE DEFAULT 0.0,
                        active BOOLEAN DEFAULT TRUE,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    @AfterAll
    static void teardownDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS users");
        }
    }

    @BeforeEach
    void clearData() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM users");
            stmt.execute("ALTER TABLE users ALTER COLUMN id RESTART WITH 1");
        }
    }

    // -- Constructor tests --

    @Test
    void constructsWithJdbcUrl() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            assertNotNull(db);
        }
    }

    @Test
    void constructsWithDataSource() throws Exception {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL(JDBC_URL);
        try (var db = new SqlStore(ds, queryFile)) {
            assertNotNull(db);
        }
    }

    @Test
    void throwsOnNullJdbcUrl() {
        assertThrows(NullPointerException.class, () -> new SqlStore((String) null, queryFile));
    }

    @Test
    void throwsOnNullDataSource() {
        assertThrows(NullPointerException.class, () ->
                new SqlStore((javax.sql.DataSource) null, queryFile));
    }

    @Test
    void throwsOnNonExistentQueryFile() {
        assertThrows(IOException.class, () ->
                new SqlStore(JDBC_URL, tempDir.resolve("nonexistent.sql")));
    }

    // -- AutoCloseable --

    @Test
    void implementsAutoCloseable() throws Exception {
        var db = new SqlStore(JDBC_URL, queryFile);
        db.close();
        // Should not throw when called twice
        db.close();
    }

    @Test
    void worksWithTryWithResources() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Test", "email", "test@test.com",
                    "status", "active", "score", 1.0));
        }
        // Connection is closed, but data should persist (DB_CLOSE_DELAY=-1)
    }

    // -- executeUpdate --

    @Test
    void insertsRow() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            Boolean result = db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "alice@example.com",
                    "status", "active", "score", 95.5));
            assertTrue(result);
        }
    }

    @Test
    void updatesRow() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Bob", "email", "bob@example.com",
                    "status", "active", "score", 80.0));

            Boolean result = db.executeUpdate("updateUserStatus", Map.of(
                    "id", 1, "status", "inactive"));
            assertTrue(result);
        }
    }

    @Test
    void deletesRow() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Charlie", "email", "charlie@example.com",
                    "status", "active", "score", 70.0));

            Boolean result = db.executeUpdate("deleteUser", Map.of("id", 1));
            assertTrue(result);
        }
    }

    @Test
    void updateReturnsFalseWhenNoRowsAffected() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            Boolean result = db.executeUpdate("deleteUser", Map.of("id", 999));
            assertFalse(result);
        }
    }

    @Test
    void updateReturnsNullForUnknownQuery() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            Boolean result = db.executeUpdate("nonExistentQuery", Map.of("x", 1));
            assertNull(result);
        }
    }

    // -- executeQuery (POJO mapping) --

    public static class User {
        public int id;
        public String name;
        public String email;
        public String status;
        public double score;
    }

    @Test
    void queriesAllUsers() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));
            db.executeUpdate("insertUser", Map.of(
                    "name", "Bob", "email", "b@b.com", "status", "inactive", "score", 80.0));

            List<User> users = db.executeQuery("getAllUsers", User.class, Map.of());
            assertEquals(2, users.size());
        }
    }

    @Test
    void queriesByParameter() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));
            db.executeUpdate("insertUser", Map.of(
                    "name", "Bob", "email", "b@b.com", "status", "inactive", "score", 80.0));

            List<User> active = db.executeQuery("getUserByStatus", User.class,
                    Map.of("status", "active"));
            assertEquals(1, active.size());
            assertEquals("Alice", active.get(0).name);
        }
    }

    @Test
    void queriesByMultipleParameters() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));

            List<User> results = db.executeQuery("getUserByIdAndStatus", User.class,
                    Map.of("id", 1, "status", "active"));
            assertEquals(1, results.size());
            assertEquals("Alice", results.get(0).name);
        }
    }

    @Test
    void returnsEmptyListForNoResults() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            List<User> users = db.executeQuery("getUserById", User.class, Map.of("id", 999));
            assertTrue(users.isEmpty());
        }
    }

    @Test
    void returnsEmptyListForUnknownQuery() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            List<User> users = db.executeQuery("nonExistent", User.class, Map.of("x", 1));
            assertTrue(users.isEmpty());
        }
    }

    @Test
    void mapsDoubleField() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 95.5));

            List<User> users = db.executeQuery("getUserById", User.class, Map.of("id", 1));
            assertEquals(95.5, users.get(0).score, 0.01);
        }
    }

    @Test
    void mapsIntField() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 0.0));

            List<User> users = db.executeQuery("getUserById", User.class, Map.of("id", 1));
            assertEquals(1, users.get(0).id);
        }
    }

    // -- executeQueryForId --

    @Test
    void queryForIdReturnsSingleResult() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));

            User user = db.executeQueryForId("getUserById", User.class, Map.of("id", 1));
            assertNotNull(user);
            assertEquals("Alice", user.name);
        }
    }

    @Test
    void queryForIdReturnsNullForNoResults() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            User user = db.executeQueryForId("getUserById", User.class, Map.of("id", 999));
            assertNull(user);
        }
    }

    @Test
    void queryForIdThrowsForMultipleResults() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));
            db.executeUpdate("insertUser", Map.of(
                    "name", "Bob", "email", "b@b.com", "status", "active", "score", 80.0));

            assertThrows(SQLException.class, () ->
                    db.executeQueryForId("getUserByStatus", User.class, Map.of("status", "active")));
        }
    }

    // -- executeGenericQuery (now returns List<Map<String, Object>>) --

    @Test
    void genericQueryReturnsListOfMaps() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeGenericUpdate(
                    "INSERT INTO users (name, email, status, score) VALUES ({name}, {email}, {status}, {score})",
                    Map.of("name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));

            List<Map<String, Object>> rows = db.executeGenericQuery(
                    "SELECT name, email FROM users WHERE name = {name}",
                    Map.of("name", "Alice"));

            assertEquals(1, rows.size());
            assertEquals("Alice", rows.get(0).get("name"));
            assertEquals("a@b.com", rows.get(0).get("email"));
        }
    }

    @Test
    void genericQueryReturnsEmptyListForNoResults() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            List<Map<String, Object>> rows = db.executeGenericQuery(
                    "SELECT * FROM users WHERE name = {name}",
                    Map.of("name", "Nobody"));

            assertNotNull(rows);
            assertTrue(rows.isEmpty());
        }
    }

    @Test
    void genericQueryPreservesColumnOrder() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeGenericUpdate(
                    "INSERT INTO users (name, email, status, score) VALUES ({name}, {email}, {status}, {score})",
                    Map.of("name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));

            List<Map<String, Object>> rows = db.executeGenericQuery(
                    "SELECT name, email, status FROM users", Map.of());

            assertEquals(1, rows.size());
            // LinkedHashMap preserves insertion order
            List<String> keys = List.copyOf(rows.get(0).keySet());
            assertEquals("name", keys.get(0));
            assertEquals("email", keys.get(1));
            assertEquals("status", keys.get(2));
        }
    }

    // -- executeGenericUpdate --

    @Test
    void genericUpdateReturnsRowCount() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            int count = db.executeGenericUpdate(
                    "INSERT INTO users (name, email, status, score) VALUES ({name}, {email}, {status}, {score})",
                    Map.of("name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));
            assertEquals(1, count);
        }
    }

    @Test
    void genericUpdateWithNoParams() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeGenericUpdate(
                    "INSERT INTO users (name, email, status, score) VALUES ({name}, {email}, {status}, {score})",
                    Map.of("name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));

            int count = db.executeGenericUpdate(
                    "DELETE FROM users WHERE 1=1", Map.of());
            assertEquals(1, count);
        }
    }

    // -- Parameter type binding --

    @Test
    void bindsIntegerParameter() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Test", "email", "t@t.com", "status", "active", "score", 0.0));

            List<User> users = db.executeQuery("getUserById", User.class, Map.of("id", 1));
            assertEquals(1, users.size());
        }
    }

    @Test
    void bindsLongParameter() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Test", "email", "t@t.com", "status", "active", "score", 0.0));

            List<User> users = db.executeQuery("getUserById", User.class, Map.of("id", 1L));
            assertEquals(1, users.size());
        }
    }

    @Test
    void bindsDoubleParameter() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 85.5));

            List<User> users = db.executeQuery("getUsersAboveScore", User.class,
                    Map.of("minScore", 80.0));
            assertEquals(1, users.size());
        }
    }

    @Test
    void bindsStringParameter() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 0.0));

            List<User> users = db.executeQuery("getUserByStatus", User.class,
                    Map.of("status", "active"));
            assertEquals(1, users.size());
        }
    }

    @Test
    void bindsTimestampParameter() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            Timestamp ts = Timestamp.valueOf("2025-01-15 10:30:00");
            db.executeUpdate("insertUserWithTimestamp", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active",
                    "score", 0.0, "createdAt", ts));

            List<User> users = db.executeQuery("getAllUsers", User.class, Map.of());
            assertEquals(1, users.size());
        }
    }

    @Test
    void bindsBooleanParameter() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUserBoolActive", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active",
                    "score", 0.0, "active", true));

            List<User> users = db.executeQuery("getAllUsers", User.class, Map.of());
            assertEquals(1, users.size());
        }
    }

    @Test
    void bindsNullParameter() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            Map<String, Object> params = new HashMap<>();
            params.put("name", "Alice");
            params.put("email", null);
            params.put("status", "active");
            params.put("score", 0.0);

            db.executeUpdate("insertUser", params);

            List<User> users = db.executeQuery("getAllUsers", User.class, Map.of());
            assertEquals(1, users.size());
            assertNull(users.get(0).email);
        }
    }

    // -- SQL injection prevention --

    @Test
    void preventsInjectionViaNamedParams() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Legit", "email", "l@b.com", "status", "active", "score", 0.0));

            List<User> users = db.executeQuery("getUserByStatus", User.class,
                    Map.of("status", "active' OR '1'='1"));
            assertTrue(users.isEmpty());
        }
    }

    @Test
    void throwsOnMissingNamedParameter() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            assertThrows(SQLException.class, () ->
                    db.executeGenericQuery(
                            "SELECT * FROM users WHERE id = {id} AND status = {status}",
                            Map.of("id", 1)));
        }
    }

    // -- Query from directory --

    @Test
    void loadsQueriesFromDirectory() throws Exception {
        Path dir = tempDir.resolve("multi-queries");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("users.sql"), """
                -- :name dirGetUser
                SELECT * FROM users WHERE id = {id}
                """);
        Files.writeString(dir.resolve("admin.sql"), """
                -- :name dirInsertUser
                INSERT INTO users (name, email, status, score) VALUES ({name}, {email}, {status}, {score})
                """);

        try (var db = new SqlStore(JDBC_URL, dir)) {
            db.executeUpdate("dirInsertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 0.0));

            List<User> users = db.executeQuery("dirGetUser", User.class, Map.of("id", 1));
            assertEquals(1, users.size());
            assertEquals("Alice", users.get(0).name);
        }
    }

    // -- DataSource mode --

    @Test
    void worksWithDataSource() throws Exception {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL(JDBC_URL);

        try (var db = new SqlStore(ds, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "DataSourceUser", "email", "ds@b.com",
                    "status", "active", "score", 0.0));

            List<User> users = db.executeQuery("getUserById", User.class, Map.of("id", 1));
            assertEquals(1, users.size());
            assertEquals("DataSourceUser", users.get(0).name);
        }
    }

    // -- Multiple operations on same instance --

    @Test
    void supportsMultipleSequentialOperations() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));
            db.executeUpdate("insertUser", Map.of(
                    "name", "Bob", "email", "b@b.com", "status", "inactive", "score", 80.0));
            db.executeUpdate("insertUser", Map.of(
                    "name", "Charlie", "email", "c@b.com", "status", "active", "score", 70.0));

            List<User> allUsers = db.executeQuery("getAllUsers", User.class, Map.of());
            assertEquals(3, allUsers.size());

            db.executeUpdate("updateUserStatus", Map.of("id", 2, "status", "active"));

            List<User> activeUsers = db.executeQuery("getUserByStatus", User.class,
                    Map.of("status", "active"));
            assertEquals(3, activeUsers.size());

            db.executeUpdate("deleteUser", Map.of("id", 3));

            List<User> remaining = db.executeQuery("getAllUsers", User.class, Map.of());
            assertEquals(2, remaining.size());
        }
    }

    // -- Edge cases --

    @Test
    void handlesEmptyMapForParameterlessQuery() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 0.0));

            List<User> users = db.executeQuery("getAllUsers", User.class, Map.of());
            assertEquals(1, users.size());
        }
    }

    @Test
    void genericQueryWithNoParamsInSql() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeGenericUpdate(
                    "INSERT INTO users (name, email, status, score) VALUES ({name}, {email}, {status}, {score})",
                    Map.of("name", "Test", "email", "t@t.com", "status", "active", "score", 0.0));

            List<Map<String, Object>> rows = db.executeGenericQuery(
                    "SELECT COUNT(*) AS cnt FROM users", Map.of());
            assertEquals(1, rows.size());
            assertNotNull(rows.get(0).get("cnt"));
        }
    }

    // -- Column type mapping via generic query --

    @Test
    void genericQueryMapsVariousColumnTypes() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS type_test (
                        int_col INT,
                        bigint_col BIGINT,
                        double_col DOUBLE,
                        real_col REAL,
                        bool_col BOOLEAN,
                        varchar_col VARCHAR(100),
                        date_col DATE,
                        timestamp_col TIMESTAMP
                    )
                    """);
            stmt.execute("DELETE FROM type_test");
            stmt.execute("""
                    INSERT INTO type_test VALUES (
                        42, 9999999999, 3.14, 2.72, TRUE, 'hello',
                        DATE '2025-01-15', TIMESTAMP '2025-01-15 10:30:00'
                    )
                    """);
        }

        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            List<Map<String, Object>> rows = db.executeGenericQuery(
                    "SELECT * FROM type_test", Map.of());
            assertEquals(1, rows.size());

            Map<String, Object> row = rows.get(0);
            assertEquals(42, row.get("int_col"));
            assertEquals(9999999999L, row.get("bigint_col"));
            assertEquals(3.14, ((Number) row.get("double_col")).doubleValue(), 0.01);
            assertEquals(true, row.get("bool_col"));
            assertEquals("hello", row.get("varchar_col"));
            assertNotNull(row.get("date_col"));
            assertNotNull(row.get("timestamp_col"));
        }

        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS type_test");
        }
    }

    // -- Column alias test --

    @Test
    void genericQueryHandlesColumnAliases() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeGenericUpdate(
                    "INSERT INTO users (name, email, status, score) VALUES ({name}, {email}, {status}, {score})",
                    Map.of("name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));

            List<Map<String, Object>> rows = db.executeGenericQuery(
                    "SELECT name AS user_name, score AS user_score FROM users", Map.of());

            assertEquals(1, rows.size());
            assertTrue(rows.get(0).containsKey("user_name"));
            assertTrue(rows.get(0).containsKey("user_score"));
        }
    }

    // -- Record mapping --

    public record UserRecord(int id, String name, String email, String status, double score) {}

    @Test
    void mapsResultToRecord() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 95.5));

            List<UserRecord> users = db.executeQuery("getUserById", UserRecord.class, Map.of("id", 1));
            assertEquals(1, users.size());
            assertEquals("Alice", users.get(0).name());
            assertEquals("a@b.com", users.get(0).email());
            assertEquals("active", users.get(0).status());
            assertEquals(95.5, users.get(0).score(), 0.01);
            assertEquals(1, users.get(0).id());
        }
    }

    @Test
    void mapsMultipleRowsToRecords() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));
            db.executeUpdate("insertUser", Map.of(
                    "name", "Bob", "email", "b@b.com", "status", "inactive", "score", 80.0));

            List<UserRecord> users = db.executeQuery("getAllUsers", UserRecord.class, Map.of());
            assertEquals(2, users.size());
            assertEquals("Alice", users.get(0).name());
            assertEquals("Bob", users.get(1).name());
        }
    }

    @Test
    void recordQueryForIdWorks() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 90.0));

            UserRecord user = db.executeQueryForId("getUserById", UserRecord.class, Map.of("id", 1));
            assertNotNull(user);
            assertEquals("Alice", user.name());
        }
    }

    // -- java.time type mapping --

    public static class UserWithLocalDateTime {
        public int id;
        public String name;
        public LocalDateTime created_at;
    }

    @Test
    void mapsTimestampToLocalDateTime() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            Timestamp ts = Timestamp.valueOf("2025-06-15 14:30:00");
            db.executeUpdate("insertUserWithTimestamp", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active",
                    "score", 0.0, "createdAt", ts));

            List<UserWithLocalDateTime> users = db.executeQuery(
                    "getAllUsers", UserWithLocalDateTime.class, Map.of());
            assertEquals(1, users.size());
            assertNotNull(users.get(0).created_at);
            assertEquals(2025, users.get(0).created_at.getYear());
            assertEquals(6, users.get(0).created_at.getMonthValue());
            assertEquals(15, users.get(0).created_at.getDayOfMonth());
            assertEquals(14, users.get(0).created_at.getHour());
            assertEquals(30, users.get(0).created_at.getMinute());
        }
    }

    public static class DateRow {
        public LocalDate date_col;
    }

    @Test
    void mapsDateToLocalDate() throws Exception {
        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS date_test (date_col DATE)");
            stmt.execute("DELETE FROM date_test");
            stmt.execute("INSERT INTO date_test VALUES (DATE '2025-03-20')");
        }

        Path dateQueryFile = tempDir.resolve("date-queries.sql");
        Files.writeString(dateQueryFile, """
                -- :name getDate
                SELECT * FROM date_test
                """);

        try (var db = new SqlStore(JDBC_URL, dateQueryFile)) {
            List<DateRow> rows = db.executeQuery("getDate", DateRow.class, Map.of());
            assertEquals(1, rows.size());
            assertEquals(LocalDate.of(2025, 3, 20), rows.get(0).date_col);
        }

        try (Connection conn = DriverManager.getConnection(JDBC_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS date_test");
        }
    }

    // -- Unmapped columns are silently skipped --

    public static class PartialUser {
        public String name;
    }

    @Test
    void skipsColumnsNotInPojo() throws Exception {
        try (var db = new SqlStore(JDBC_URL, queryFile)) {
            db.executeUpdate("insertUser", Map.of(
                    "name", "Alice", "email", "a@b.com", "status", "active", "score", 0.0));

            List<PartialUser> users = db.executeQuery("getAllUsers", PartialUser.class, Map.of());
            assertEquals(1, users.size());
            assertEquals("Alice", users.get(0).name);
        }
    }
}
