package no.skaperiet.sqlstore.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QueryRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsQueriesFromSingleFile() throws IOException {
        Path file = tempDir.resolve("queries.sql");
        Files.writeString(file, """
                -- :name getUser
                SELECT * FROM users WHERE id = {id}

                -- :name insertUser
                INSERT INTO users (name) VALUES ({name})
                """);

        QueryRegistry registry = QueryRegistry.load(file);
        assertNotNull(registry.findQuery("getUser", Set.of("id")));
        assertNotNull(registry.findQuery("insertUser", Set.of("name")));
    }

    @Test
    void loadsQueriesFromDirectory() throws IOException {
        Path usersFile = tempDir.resolve("users.sql");
        Files.writeString(usersFile, """
                -- :name getUser
                SELECT * FROM users WHERE id = {id}
                """);

        Path ordersFile = tempDir.resolve("orders.sql");
        Files.writeString(ordersFile, """
                -- :name getOrder
                SELECT * FROM orders WHERE id = {id}
                """);

        // Non-.sql file should be ignored
        Files.writeString(tempDir.resolve("readme.txt"), "not a sql file");

        QueryRegistry registry = QueryRegistry.load(tempDir);
        assertNotNull(registry.findQuery("getUser", Set.of("id")));
        assertNotNull(registry.findQuery("getOrder", Set.of("id")));
    }

    @Test
    void returnsNullForUnknownQuery() throws IOException {
        Path file = tempDir.resolve("queries.sql");
        Files.writeString(file, """
                -- :name getUser
                SELECT * FROM users WHERE id = {id}
                """);

        QueryRegistry registry = QueryRegistry.load(file);
        assertNull(registry.findQuery("nonExistent", Set.of("id")));
    }

    @Test
    void returnsNullForWrongParamNames() throws IOException {
        Path file = tempDir.resolve("queries.sql");
        Files.writeString(file, """
                -- :name getUser
                SELECT * FROM users WHERE id = {id}
                """);

        QueryRegistry registry = QueryRegistry.load(file);
        assertNull(registry.findQuery("getUser", Set.of("name")));
    }

    @Test
    void distinguishesQueriesByParamSignature() throws IOException {
        Path file = tempDir.resolve("queries.sql");
        Files.writeString(file, """
                -- :name getUser
                SELECT * FROM users WHERE id = {id}

                -- :name getUser
                SELECT * FROM users WHERE email = {email}
                """);

        QueryRegistry registry = QueryRegistry.load(file);

        SqlQuery byId = registry.findQuery("getUser", Set.of("id"));
        assertNotNull(byId);
        assertTrue(byId.sql().contains("id = {id}"));

        SqlQuery byEmail = registry.findQuery("getUser", Set.of("email"));
        assertNotNull(byEmail);
        assertTrue(byEmail.sql().contains("email = {email}"));
    }

    @Test
    void findsQueryWithNoParameters() throws IOException {
        Path file = tempDir.resolve("queries.sql");
        Files.writeString(file, """
                -- :name getAllUsers
                SELECT * FROM users
                """);

        QueryRegistry registry = QueryRegistry.load(file);
        assertNotNull(registry.findQuery("getAllUsers", Set.of()));
    }

    @Test
    void loadsEmptyFile() throws IOException {
        Path file = tempDir.resolve("empty.sql");
        Files.writeString(file, "");

        QueryRegistry registry = QueryRegistry.load(file);
        assertNull(registry.findQuery("anything", Set.of()));
    }

    @Test
    void loadsEmptyDirectory() throws IOException {
        Path subDir = tempDir.resolve("empty-dir");
        Files.createDirectory(subDir);

        QueryRegistry registry = QueryRegistry.load(subDir);
        assertNull(registry.findQuery("anything", Set.of()));
    }

    @Test
    void throwsForNonExistentFile() {
        Path nonExistent = tempDir.resolve("does-not-exist.sql");
        assertThrows(IOException.class, () -> QueryRegistry.load(nonExistent));
    }

    @Test
    void mergesQueriesFromMultipleFilesInDirectory() throws IOException {
        Files.writeString(tempDir.resolve("a.sql"), """
                -- :name queryA
                SELECT 'A'
                """);
        Files.writeString(tempDir.resolve("b.sql"), """
                -- :name queryB
                SELECT 'B'
                """);
        Files.writeString(tempDir.resolve("c.sql"), """
                -- :name queryC
                SELECT 'C'
                """);

        QueryRegistry registry = QueryRegistry.load(tempDir);
        assertNotNull(registry.findQuery("queryA", Set.of()));
        assertNotNull(registry.findQuery("queryB", Set.of()));
        assertNotNull(registry.findQuery("queryC", Set.of()));
    }
}
