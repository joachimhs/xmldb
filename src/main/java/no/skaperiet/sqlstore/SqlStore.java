package no.skaperiet.sqlstore;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import no.skaperiet.sqlstore.query.QueryRegistry;
import no.skaperiet.sqlstore.query.SqlQuery;

/**
 * Main entry point for executing named SQL queries.
 * Manages database connections and executes queries defined in .sql files.
 *
 * <p>Supports two construction modes:</p>
 * <ul>
 *   <li>DataSource-based (preferred for connection pools, JNDI, testing)</li>
 *   <li>Direct JDBC URL (for simple/standalone usage)</li>
 * </ul>
 *
 * <p>Implements AutoCloseable for use with try-with-resources.</p>
 */
public class SqlStore implements AutoCloseable {

    private static final Logger log = Logger.getLogger(SqlStore.class.getName());
    private static final Pattern NAMED_PARAM_PATTERN = Pattern.compile("\\{(\\w+)\\}");

    private final DataSource dataSource;
    private final String jdbcUrl;
    private final QueryRegistry registry;
    private Connection cachedConnection;

    /**
     * Creates a SqlStore instance using a DataSource for connection management.
     * This is the preferred constructor for production use with connection pools.
     *
     * @param dataSource the DataSource to obtain connections from
     * @param queryPath path to a .sql file or directory of .sql files
     * @throws IOException if the query files cannot be read
     */
    public SqlStore(DataSource dataSource, Path queryPath) throws IOException {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbcUrl = null;
        this.registry = QueryRegistry.load(queryPath);
    }

    /**
     * Creates a SqlStore instance using a direct JDBC URL.
     * A single connection is cached and reused for the lifetime of this instance.
     *
     * @param jdbcUrl a full JDBC URL (e.g. "jdbc:h2:mem:test")
     * @param queryPath path to a .sql file or directory of .sql files
     * @throws IOException if the query files cannot be read
     */
    public SqlStore(String jdbcUrl, Path queryPath) throws IOException {
        this.dataSource = null;
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.registry = QueryRegistry.load(queryPath);
    }

    /**
     * Obtains a database connection.
     * For DataSource mode: returns a new connection from the pool each time.
     * For JDBC URL mode: returns a cached connection, creating one if needed.
     */
    private Connection getConnection() throws SQLException {
        if (dataSource != null) {
            return dataSource.getConnection();
        } else {
            if (cachedConnection == null || cachedConnection.isClosed()) {
                cachedConnection = DriverManager.getConnection(jdbcUrl);
            }
            return cachedConnection;
        }
    }

    /**
     * Returns true if this instance uses a DataSource (pooled connections).
     */
    private boolean isPooled() {
        return dataSource != null;
    }

    /**
     * Executes a named SELECT query and maps results to POJOs or records.
     *
     * @param name the query name as defined in the .sql file
     * @param clazz the target class for result mapping
     * @param params named parameters as key-value pairs
     * @return list of mapped objects, empty list if no results or query not found
     * @throws SQLException on database errors
     */
    public <T> List<T> executeQuery(String name, Class<T> clazz, Map<String, Object> params)
            throws SQLException {
        SqlQuery query = registry.findQuery(name, params.keySet());
        if (query == null) {
            log.warning("No query matching name and params: " + name + " " + params.keySet());
            return List.of();
        }

        Connection conn = getConnection();
        try (PreparedStatement stmt = prepareNamedStatement(conn, query.sql(), params);
             ResultSet rs = stmt.executeQuery()) {
            return RowMapper.mapRows(rs, clazz);
        } finally {
            if (isPooled()) {
                conn.close();
            }
        }
    }

    /**
     * Executes a named SELECT query expecting zero or one result.
     *
     * @param name the query name
     * @param clazz the target class
     * @param params named parameters
     * @return the single result, or null if no results
     * @throws SQLException if more than one row is returned, or on database errors
     */
    public <T> T executeQueryForId(String name, Class<T> clazz, Map<String, Object> params)
            throws SQLException {
        List<T> results = executeQuery(name, clazz, params);

        if (results.size() > 1) {
            throw new SQLException("Expected single record from query: " + name + " got " + results.size());
        } else if (results.isEmpty()) {
            return null;
        }

        return results.get(0);
    }

    /**
     * Executes a named INSERT/UPDATE/DELETE query.
     *
     * @param name the query name
     * @param params named parameters
     * @return true if rows were affected, false if zero rows affected, null if query not found
     * @throws SQLException on database errors
     */
    public Boolean executeUpdate(String name, Map<String, Object> params) throws SQLException {
        SqlQuery query = registry.findQuery(name, params.keySet());
        if (query == null) {
            log.warning("No query matching name and params: " + name + " " + params.keySet());
            return null;
        }

        Connection conn = getConnection();
        try (PreparedStatement stmt = prepareNamedStatement(conn, query.sql(), params)) {
            log.fine(query.sql());
            log.fine("Parameters: " + params);

            int numUpdatedRows = stmt.executeUpdate();
            return numUpdatedRows > 0;
        } finally {
            if (isPooled()) {
                conn.close();
            }
        }
    }

    /**
     * Executes an ad-hoc SQL SELECT query with named parameters.
     * Parameters in the SQL are specified as {paramName} and are bound safely
     * via PreparedStatement to prevent SQL injection.
     *
     * @param sql the SQL query with {paramName} placeholders
     * @param namedParams parameter values keyed by name
     * @return a list of rows, each represented as a LinkedHashMap preserving column order
     * @throws SQLException on database errors or missing parameter values
     */
    public List<Map<String, Object>> executeGenericQuery(String sql, Map<String, Object> namedParams)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();

        Connection conn = getConnection();
        try (PreparedStatement stmt = prepareNamedStatement(conn, sql, namedParams);
             ResultSet rs = stmt.executeQuery()) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String name = meta.getColumnLabel(i);
                    if (name == null || name.isEmpty()) {
                        name = meta.getColumnName(i);
                    }
                    row.put(name, rs.getObject(i));
                }
                rows.add(row);
            }
        } finally {
            if (isPooled()) {
                conn.close();
            }
        }

        return rows;
    }

    /**
     * Executes an ad-hoc SQL INSERT/UPDATE/DELETE with named parameters.
     * Parameters in the SQL are specified as {paramName} and are bound safely
     * via PreparedStatement to prevent SQL injection.
     *
     * @param sql the SQL statement with {paramName} placeholders
     * @param namedParams parameter values keyed by name
     * @return the number of rows affected
     * @throws SQLException on database errors or missing parameter values
     */
    public int executeGenericUpdate(String sql, Map<String, Object> namedParams) throws SQLException {
        Connection conn = getConnection();
        try (PreparedStatement stmt = prepareNamedStatement(conn, sql, namedParams)) {
            return stmt.executeUpdate();
        } finally {
            if (isPooled()) {
                conn.close();
            }
        }
    }

    /**
     * Closes the database connection. For DataSource mode this is a no-op
     * (connections are returned to pool after each operation).
     * For JDBC URL mode, closes the cached connection.
     */
    @Override
    public void close() {
        if (cachedConnection != null) {
            try {
                cachedConnection.close();
            } catch (SQLException e) {
                log.warning("Error closing connection: " + e.getMessage());
            }
            cachedConnection = null;
        }
    }

    // -- Private helper methods --

    /**
     * Parses a SQL string containing {paramName} placeholders, replaces them with ?,
     * and binds the corresponding values from the map to a PreparedStatement.
     */
    private PreparedStatement prepareNamedStatement(Connection conn, String sql,
            Map<String, Object> namedParams) throws SQLException {
        List<Object> orderedValues = new ArrayList<>();
        Matcher matcher = NAMED_PARAM_PATTERN.matcher(sql);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String paramName = matcher.group(1);
            if (!namedParams.containsKey(paramName)) {
                throw new SQLException("Missing value for named parameter: {" + paramName + "}");
            }
            orderedValues.add(namedParams.get(paramName));
            matcher.appendReplacement(sb, "?");
        }
        matcher.appendTail(sb);

        PreparedStatement stmt = conn.prepareStatement(sb.toString());
        for (int i = 0; i < orderedValues.size(); i++) {
            bindParameter(stmt, i + 1, orderedValues.get(i));
        }
        return stmt;
    }

    /**
     * Binds a single parameter value to a PreparedStatement at the given index.
     * Uses instanceof pattern matching (Java 17) for type dispatch.
     */
    private void bindParameter(PreparedStatement stmt, int index, Object value) throws SQLException {
        if (value == null)                       { stmt.setNull(index, Types.NULL); }
        else if (value instanceof Integer v)     { stmt.setInt(index, v); }
        else if (value instanceof Long v)        { stmt.setLong(index, v); }
        else if (value instanceof Double v)      { stmt.setDouble(index, v); }
        else if (value instanceof Float v)       { stmt.setFloat(index, v); }
        else if (value instanceof Boolean v)     { stmt.setBoolean(index, v); }
        else if (value instanceof Timestamp v)   { stmt.setTimestamp(index, v); }
        else if (value instanceof String v)      { stmt.setString(index, v); }
        else                                     { stmt.setObject(index, value); }
    }
}
