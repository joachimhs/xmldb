package no.skaperiet.sqlstore.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlFileParserTest {

    @Test
    void parsesSingleQuery() {
        String content = """
                -- :name getUser
                SELECT * FROM users WHERE id = {id}
                """;

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertEquals(1, queries.size());
        assertEquals("getUser", queries.get(0).name());
        assertEquals("SELECT * FROM users WHERE id = {id}", queries.get(0).sql());
        assertEquals(Set.of("id"), queries.get(0).parameterNames());
    }

    @Test
    void parsesMultipleQueries() {
        String content = """
                -- :name query1
                SELECT 1

                -- :name query2
                SELECT 2

                -- :name query3
                SELECT 3
                """;

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertEquals(3, queries.size());
        assertEquals("query1", queries.get(0).name());
        assertEquals("query2", queries.get(1).name());
        assertEquals("query3", queries.get(2).name());
    }

    @Test
    void preservesMultiLineSql() {
        String content = """
                -- :name complexQuery
                SELECT u.name, u.email
                FROM users u
                JOIN orders o ON o.user_id = u.id
                WHERE u.status = {status}
                ORDER BY u.name
                """;

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertEquals(1, queries.size());
        String sql = queries.get(0).sql();
        assertTrue(sql.contains("SELECT u.name, u.email"));
        assertTrue(sql.contains("FROM users u"));
        assertTrue(sql.contains("JOIN orders o ON o.user_id = u.id"));
        assertTrue(sql.contains("WHERE u.status = {status}"));
        assertTrue(sql.contains("ORDER BY u.name"));
    }

    @Test
    void preservesSqlCommentsInBody() {
        String content = """
                -- :name queryWithComments
                SELECT *
                -- this is a SQL comment
                FROM users
                WHERE status = {status}
                """;

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertEquals(1, queries.size());
        assertTrue(queries.get(0).sql().contains("-- this is a SQL comment"));
    }

    @Test
    void handlesBlankLinesBetweenQueries() {
        String content = """
                -- :name q1
                SELECT 1


                -- :name q2
                SELECT 2
                """;

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertEquals(2, queries.size());
        assertEquals("SELECT 1", queries.get(0).sql());
        assertEquals("SELECT 2", queries.get(1).sql());
    }

    @Test
    void trimsWhitespaceFromSql() {
        String content = """
                -- :name q

                  SELECT * FROM users

                """;

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertEquals(1, queries.size());
        assertEquals("SELECT * FROM users", queries.get(0).sql());
    }

    @Test
    void returnsEmptyListForEmptyContent() {
        List<SqlQuery> queries = SqlFileParser.parse("");
        assertTrue(queries.isEmpty());
    }

    @Test
    void returnsEmptyListForContentWithoutNameDeclarations() {
        String content = """
                SELECT * FROM users
                WHERE id = 1
                """;

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertTrue(queries.isEmpty());
    }

    @Test
    void skipsNameDeclarationWithEmptySql() {
        String content = """
                -- :name emptyQuery
                -- :name realQuery
                SELECT 1
                """;

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertEquals(1, queries.size());
        assertEquals("realQuery", queries.get(0).name());
    }

    @Test
    void handlesQueryWithNoParameters() {
        String content = """
                -- :name allUsers
                SELECT * FROM users
                """;

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertEquals(1, queries.size());
        assertEquals(Set.of(), queries.get(0).parameterNames());
    }

    @Test
    void handlesQueryWithMultipleParameterReferences() {
        String content = """
                -- :name search
                SELECT * FROM t WHERE a = {x} AND b = {y} AND c = {x}
                """;

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertEquals(1, queries.size());
        assertEquals(Set.of("x", "y"), queries.get(0).parameterNames());
    }

    @Test
    void handlesWindowsLineEndings() {
        String content = "-- :name q\r\nSELECT 1\r\n";

        List<SqlQuery> queries = SqlFileParser.parse(content);
        assertEquals(1, queries.size());
        assertEquals("q", queries.get(0).name());
        assertEquals("SELECT 1", queries.get(0).sql());
    }
}
