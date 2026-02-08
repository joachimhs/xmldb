package no.skaperiet.sqlstore.query;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlQueryTest {

    @Test
    void extractsParameterNamesFromSql() {
        var query = new SqlQuery("test", "SELECT * FROM users WHERE id = {id} AND status = {status}");
        assertEquals("test", query.name());
        assertEquals(Set.of("id", "status"), query.parameterNames());
    }

    @Test
    void handlesNoParameters() {
        var query = new SqlQuery("noParams", "SELECT * FROM users");
        assertEquals(Set.of(), query.parameterNames());
    }

    @Test
    void handlesDuplicateParameterNames() {
        var query = new SqlQuery("dup", "SELECT * FROM t WHERE a = {x} OR b = {x}");
        assertEquals(Set.of("x"), query.parameterNames());
    }

    @Test
    void preservesSql() {
        String sql = "SELECT * FROM users WHERE id = {id}";
        var query = new SqlQuery("q", sql);
        assertEquals(sql, query.sql());
    }

    @Test
    void threeArgConstructorAcceptsExplicitParamNames() {
        var query = new SqlQuery("q", "SELECT 1", Set.of("a", "b"));
        assertEquals(Set.of("a", "b"), query.parameterNames());
    }

    @Test
    void matchesReturnsTrueForExactMatch() {
        var query = new SqlQuery("q", "SELECT * FROM t WHERE a = {x} AND b = {y}");
        assertTrue(query.matches(Set.of("x", "y")));
    }

    @Test
    void matchesReturnsFalseForMismatch() {
        var query = new SqlQuery("q", "SELECT * FROM t WHERE a = {x}");
        assertFalse(query.matches(Set.of("x", "y")));
    }

    @Test
    void matchesReturnsFalseForSubset() {
        var query = new SqlQuery("q", "SELECT * FROM t WHERE a = {x} AND b = {y}");
        assertFalse(query.matches(Set.of("x")));
    }

    @Test
    void matchesReturnsTrueForEmptyParams() {
        var query = new SqlQuery("q", "SELECT 1");
        assertTrue(query.matches(Set.of()));
    }

    @Test
    void recordEquality() {
        var q1 = new SqlQuery("q", "SELECT 1", Set.of());
        var q2 = new SqlQuery("q", "SELECT 1", Set.of());
        assertEquals(q1, q2);
        assertEquals(q1.hashCode(), q2.hashCode());
    }

    @Test
    void recordInequality() {
        var q1 = new SqlQuery("q1", "SELECT 1");
        var q2 = new SqlQuery("q2", "SELECT 1");
        assertNotEquals(q1, q2);
    }

    @Test
    void toStringContainsName() {
        var query = new SqlQuery("myQuery", "SELECT 1");
        assertTrue(query.toString().contains("myQuery"));
    }
}
