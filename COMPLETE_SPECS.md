# SqlStore 1.0 — Complete Specification

A small, lean Java library for executing externalized SQL queries with named parameters
and automatic result mapping. Zero runtime dependencies.

---

## Table of Contents

1.  [Overview](#1-overview)
2.  [Maven Coordinates](#2-maven-coordinates)
3.  [Dependencies](#3-dependencies)
4.  [System Requirements](#4-system-requirements)
5.  [Project Structure](#5-project-structure)
6.  [SQL File Format](#6-sql-file-format)
7.  [Query Loading](#7-query-loading)
8.  [Query Resolution](#8-query-resolution)
9.  [Connection Management](#9-connection-management)
10. [Public API](#10-public-api)
11. [Named Parameters](#11-named-parameters)
12. [Parameter Type Binding](#12-parameter-type-binding)
13. [Result Mapping](#13-result-mapping)
14. [Type Conversion Table](#14-type-conversion-table)
15. [Security](#15-security)
16. [Logging](#16-logging)
17. [Error Handling](#17-error-handling)
18. [Complete Usage Examples](#18-complete-usage-examples)
19. [Test Suite](#19-test-suite)
20. [Internal Architecture](#20-internal-architecture)

---

## 1. Overview

SqlStore is a lightweight Java library that:

- **Externalizes SQL** into `.sql` files, keeping SQL out of Java code
- **Names queries** using a `-- :name` comment convention
- **Binds named parameters** via `{paramName}` placeholders, converted to `?` at runtime
- **Maps results** directly to POJOs or Java 17 records via reflection
- **Prevents SQL injection** — all parameters go through `PreparedStatement`
- **Has zero runtime dependencies** — only `java.sql` and `java.base`

### Design Principles

| Principle | Implementation |
|-----------|---------------|
| Small and lean | 5 source files, ~450 lines total |
| Zero dependencies | No Gson, no Jackson, no Spring — only JDK |
| SQL stays in SQL | `.sql` files with IDE syntax highlighting |
| No magic | No annotations, no proxies, no bytecode generation |
| Safe by default | All parameters bound via PreparedStatement |

---

## 2. Maven Coordinates

```xml
<groupId>no.skaperiet.sqlstore</groupId>
<artifactId>sqlstore</artifactId>
<version>1.0</version>
<packaging>jar</packaging>
```

---

## 3. Dependencies

### Runtime

**None.** SqlStore has zero external runtime dependencies.

The only required modules are `java.base` and `java.sql`, which are part of the JDK.

### Test

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `org.junit.jupiter:junit-jupiter` | 5.11.4 | Test framework |
| `com.h2database:h2` | 2.3.232 | In-memory database for integration tests |

---

## 4. System Requirements

| Requirement | Value |
|-------------|-------|
| Java version | 17 or higher |
| Build tool | Maven 3.x |
| Compiler plugin | maven-compiler-plugin 3.13.0 |
| Test runner | maven-surefire-plugin 3.5.2 |

Java 17 features used: records, switch expressions, instanceof pattern matching, text blocks, `Set.copyOf()`, `List.of()`.

---

## 5. Project Structure

```
no.skaperiet.sqlstore
├── SqlStore.java            Main entry point (302 lines)
├── RowMapper.java           ResultSet → POJO/record mapper (146 lines, package-private)
└── query/
    ├── SqlQuery.java        Immutable query record (38 lines)
    ├── SqlFileParser.java   .sql file parser (60 lines)
    └── QueryRegistry.java   Query storage and lookup (75 lines)
```

| Class | Visibility | Purpose |
|-------|-----------|---------|
| `SqlStore` | `public` | User-facing API: constructors, execute methods, close |
| `RowMapper` | package-private | Maps ResultSet rows to POJOs or records |
| `SqlQuery` | `public` | Immutable record: name + SQL + parameter names |
| `SqlFileParser` | `public` | Parses `.sql` file content into `SqlQuery` objects |
| `QueryRegistry` | `public` | Loads and indexes queries by name, finds by name + params |

---

## 6. SQL File Format

Queries are defined in plain `.sql` files using the `-- :name` comment convention.

### Syntax

```sql
-- :name queryName
SQL statement here
which can span multiple lines
and reference parameters as {paramName}
```

### Rules

| Rule | Detail |
|------|--------|
| Name declaration | A line beginning with `-- :name ` followed by the query name |
| Query body | All lines after the name declaration until the next `-- :name` or end of file |
| Parameters | `{paramName}` where `paramName` matches `\w+` (letters, digits, underscores) |
| SQL comments | `-- comments` inside a query body are preserved (only `-- :name` is special) |
| Whitespace | Leading/trailing whitespace on the SQL body is trimmed |
| Empty queries | A `-- :name` with no SQL body before the next `-- :name` is skipped |
| Line endings | Both `\n` (Unix) and `\r\n` (Windows) are supported |
| Multiple queries per file | Unlimited — each `-- :name` starts a new query |
| Overloaded names | Multiple queries can share the same name if their parameter sets differ |

### Example File

```sql
-- :name getAllUsers
SELECT * FROM users

-- :name getUserById
SELECT * FROM users WHERE id = {id}

-- :name getUserByEmail
SELECT * FROM users WHERE email = {email}

-- :name insertUser
INSERT INTO users (name, email, status)
VALUES ({name}, {email}, {status})

-- :name updateUserStatus
UPDATE users SET status = {status} WHERE id = {id}

-- :name deleteUser
DELETE FROM users WHERE id = {id}
```

In this example, `getUserById` and `getUserByEmail` share no name conflict because their
parameter sets (`{id}` vs `{email}`) are different.

---

## 7. Query Loading

Queries are loaded at construction time via `QueryRegistry.load(Path)`.

### Single File

```java
var store = new SqlStore(jdbcUrl, Path.of("queries.sql"));
```

Reads the file, parses all `-- :name` queries, and indexes them.

### Directory

```java
var store = new SqlStore(jdbcUrl, Path.of("queries/"));
```

- Scans the directory for files ending in `.sql`
- Non-`.sql` files are ignored
- Files are loaded in **sorted order** (alphabetical by filename)
- All queries from all files are merged into a single registry
- Subdirectories are **not** recursed into

### Error Conditions

| Condition | Behavior |
|-----------|----------|
| Path does not exist | Throws `IOException` |
| File is empty | Returns an empty registry (no queries) |
| Directory is empty | Returns an empty registry (no queries) |
| Directory has no `.sql` files | Returns an empty registry (no queries) |
| File is not readable | Throws `IOException` |

---

## 8. Query Resolution

When a method like `executeQuery("getUserById", ...)` is called, the registry resolves
the query by **name AND parameter signature**.

### Algorithm

1. Look up all queries with the given name → candidate list
2. For each candidate, compare the caller's parameter key set against the query's `{param}` set
3. Return the first candidate where the sets are **exactly equal**
4. Return `null` if no match

### Overloading

Two queries may share the same name if their parameter sets differ:

```sql
-- :name getUser
SELECT * FROM users WHERE id = {id}

-- :name getUser
SELECT * FROM users WHERE email = {email}
```

```java
// Resolves to the first query (parameter set: {id})
store.executeQuery("getUser", User.class, Map.of("id", 1));

// Resolves to the second query (parameter set: {email})
store.executeQuery("getUser", User.class, Map.of("email", "alice@example.com"));
```

### Match Strictness

The match is **exact** — no partial matching, no superset matching:

| Query params | Caller params | Matches? |
|-------------|---------------|----------|
| `{id}` | `Map.of("id", 1)` | Yes |
| `{id}` | `Map.of("id", 1, "name", "x")` | No — extra param |
| `{id, name}` | `Map.of("id", 1)` | No — missing param |
| (none) | `Map.of()` | Yes |

---

## 9. Connection Management

SqlStore supports two connection modes, selected at construction time.

### DataSource Mode (Recommended)

```java
var store = new SqlStore(dataSource, queryPath);
```

- A **new connection** is obtained from the DataSource for each operation
- The connection is **returned (closed)** in the `finally` block after each operation
- Designed for use with connection pools (HikariCP, DBCP, etc.)
- `close()` is a no-op in this mode

### JDBC URL Mode

```java
var store = new SqlStore("jdbc:h2:mem:test", queryPath);
```

- A **single connection** is created lazily on first use and cached
- The same connection is reused for all subsequent operations
- If the connection is closed or broken, a new one is created automatically
- `close()` closes the cached connection

### AutoCloseable

SqlStore implements `AutoCloseable` for use with try-with-resources:

```java
try (var store = new SqlStore(jdbcUrl, queryPath)) {
    // use store
} // connection is closed automatically
```

- `close()` can be called multiple times safely (idempotent)
- Errors during close are logged, not thrown

---

## 10. Public API

### Constructors

```java
public SqlStore(DataSource dataSource, Path queryPath) throws IOException
public SqlStore(String jdbcUrl, Path queryPath) throws IOException
```

| Parameter | Description |
|-----------|-------------|
| `dataSource` | A `javax.sql.DataSource` for pooled connections. Must not be null. |
| `jdbcUrl` | A JDBC URL string (e.g., `"jdbc:postgresql://localhost/mydb"`). Must not be null. |
| `queryPath` | Path to a `.sql` file or a directory containing `.sql` files. |

Both constructors throw `NullPointerException` if the connection parameter is null,
and `IOException` if the query path cannot be read.

---

### executeQuery

```java
public <T> List<T> executeQuery(String name, Class<T> clazz, Map<String, Object> params)
        throws SQLException
```

Executes a named SELECT query and maps each result row to an instance of `clazz`.

| Parameter | Description |
|-----------|-------------|
| `name` | The query name as defined in the `.sql` file |
| `clazz` | Target class — can be a regular class (with no-arg constructor) or a Java 17 record |
| `params` | Named parameters as key-value pairs |

**Returns:** A list of mapped objects. Empty list if no rows found or query name not matched.

**Behavior on unknown query:** Returns `List.of()` and logs a warning. Does not throw.

---

### executeQueryForId

```java
public <T> T executeQueryForId(String name, Class<T> clazz, Map<String, Object> params)
        throws SQLException
```

Executes a named SELECT query expecting exactly zero or one result.

| Return value | Condition |
|-------------|-----------|
| The mapped object | Exactly one row returned |
| `null` | Zero rows returned |
| Throws `SQLException` | More than one row returned |

---

### executeUpdate

```java
public Boolean executeUpdate(String name, Map<String, Object> params) throws SQLException
```

Executes a named INSERT, UPDATE, or DELETE query.

| Return value | Condition |
|-------------|-----------|
| `true` | One or more rows were affected |
| `false` | Zero rows were affected |
| `null` | Query name not found in the registry |

---

### executeGenericQuery

```java
public List<Map<String, Object>> executeGenericQuery(String sql, Map<String, Object> namedParams)
        throws SQLException
```

Executes an **ad-hoc SQL string** (not a named query) with named parameter support.

| Parameter | Description |
|-----------|-------------|
| `sql` | A SQL SELECT statement with `{paramName}` placeholders |
| `namedParams` | Parameter values keyed by name |

**Returns:** A list of rows. Each row is a `LinkedHashMap<String, Object>` preserving
column order. Values are JDBC native types (as returned by `ResultSet.getObject()`).
Returns an empty list if no rows match.

This method is useful for:
- One-off queries that don't warrant a named query entry
- Dynamic SQL constructed at runtime
- Admin/debugging tools

---

### executeGenericUpdate

```java
public int executeGenericUpdate(String sql, Map<String, Object> namedParams) throws SQLException
```

Executes an ad-hoc SQL INSERT/UPDATE/DELETE string with named parameter support.

**Returns:** The number of rows affected.

---

### close

```java
public void close()
```

Closes the database connection.

- **DataSource mode:** No-op (connections are returned after each operation)
- **JDBC URL mode:** Closes the cached connection
- Safe to call multiple times
- Logs any `SQLException` during close, does not rethrow

---

## 11. Named Parameters

Parameters are referenced in SQL using `{paramName}` syntax.

### Placeholder Format

```
{paramName}
```

Where `paramName` matches the regex `\w+` (one or more word characters: `[a-zA-Z0-9_]`).

### How It Works

At execution time, the SQL string is processed:

1. Every `{paramName}` is replaced with `?`
2. The corresponding value is looked up in the `Map<String, Object>` parameter map
3. Values are bound to the `PreparedStatement` in the order they appear in the SQL
4. If a parameter appears multiple times in the SQL, the value is bound once for each occurrence

### Example

```sql
-- SQL in file
SELECT * FROM users WHERE status = {status} AND score > {minScore}
```

```java
// Java call
store.executeQuery("findUsers", User.class,
    Map.of("status", "active", "minScore", 80.0));
```

```sql
-- Sent to database as
SELECT * FROM users WHERE status = ? AND score > ?
-- with parameters: ["active", 80.0]
```

### Missing Parameters

If the SQL references a `{paramName}` that is not present in the map, a `SQLException`
is thrown with message: `Missing value for named parameter: {paramName}`

---

## 12. Parameter Type Binding

When binding parameter values to the `PreparedStatement`, SqlStore dispatches based on
the Java type of the value:

| Java Type | JDBC Method |
|-----------|------------|
| `null` | `stmt.setNull(index, Types.NULL)` |
| `Integer` | `stmt.setInt(index, v)` |
| `Long` | `stmt.setLong(index, v)` |
| `Double` | `stmt.setDouble(index, v)` |
| `Float` | `stmt.setFloat(index, v)` |
| `Boolean` | `stmt.setBoolean(index, v)` |
| `java.sql.Timestamp` | `stmt.setTimestamp(index, v)` |
| `String` | `stmt.setString(index, v)` |
| Any other type | `stmt.setObject(index, v)` (JDBC driver decides) |

The fallback `setObject` allows types like `BigDecimal`, `java.sql.Date`, `byte[]`, etc.
to work without explicit handling.

---

## 13. Result Mapping

SqlStore maps ResultSet rows to Java objects using direct reflection. There is no
intermediate JSON representation — values transfer directly from `ResultSet` to fields.

### POJO Mapping

For regular classes, SqlStore:

1. Resolves column-to-field mapping **once** before the row loop (by matching column labels to field names)
2. Creates a new instance via the **no-arg constructor** for each row
3. Sets each field's value using `Field.set()` with `setAccessible(true)`
4. Columns with no matching field are **silently skipped**
5. Fields with no matching column retain their default value

```java
// POJO — must have a public no-arg constructor and public fields
public class User {
    public int id;
    public String name;
    public String email;
}

List<User> users = store.executeQuery("getAllUsers", User.class, Map.of());
```

### Record Mapping

For Java 17 records, SqlStore:

1. Reads the record's `RecordComponent[]` to determine component names and types
2. Builds a column-name → column-index lookup from the ResultSet metadata
3. Reads each value from the ResultSet in component order
4. Invokes the **canonical constructor** with all values

```java
// Record — immutable, no setter needed
public record User(int id, String name, String email) {}

List<User> users = store.executeQuery("getAllUsers", User.class, Map.of());
```

### Column Name Matching

Column matching uses the **column label** (from `AS` alias) if available, falling back
to the **column name**. The match is **case-sensitive** and **exact**:

```sql
SELECT name AS userName FROM users
-- Matches field: userName (not name)
```

### Partial Mapping

It is valid to map into a class that has fewer fields than columns:

```java
public class PartialUser {
    public String name;  // only this field is populated
}

// SELECT * FROM users — columns id, name, email, status, score are returned
// Only "name" is mapped; others are skipped
List<PartialUser> users = store.executeQuery("getAllUsers", PartialUser.class, Map.of());
```

---

## 14. Type Conversion Table

When mapping ResultSet values to POJO fields or record components, RowMapper converts
based on the **target field type**:

| Target Field Type | ResultSet Method | Notes |
|-------------------|-----------------|-------|
| `int` / `Integer` | `rs.getInt()` | |
| `long` / `Long` | `rs.getLong()` | |
| `double` / `Double` | `rs.getDouble()` | |
| `float` / `Float` | `rs.getFloat()` | |
| `boolean` / `Boolean` | `rs.getBoolean()` | |
| `short` / `Short` | `rs.getShort()` | |
| `byte` / `Byte` | `rs.getByte()` | |
| `String` | `rs.getString()` | |
| `java.math.BigDecimal` | `rs.getBigDecimal()` | |
| `java.sql.Timestamp` | `rs.getTimestamp()` | |
| `java.sql.Date` | `rs.getDate()` | |
| `java.sql.Time` | `rs.getTime()` | |
| `java.time.LocalDate` | `rs.getDate().toLocalDate()` | Null-safe |
| `java.time.LocalDateTime` | `rs.getTimestamp().toLocalDateTime()` | Null-safe |
| `java.time.LocalTime` | `rs.getTime().toLocalTime()` | Null-safe |
| Any other type | `rs.getObject()` | Fallback |

**Null handling:** If the database column is NULL, `null` is returned for all object
types. For primitive fields (`int`, `double`, etc.), Java's default value is assigned
(0, 0.0, false).

---

## 15. Security

### SQL Injection Prevention

All parameter values are bound via `PreparedStatement.set*()` methods. Parameter values
are **never** interpolated into the SQL string. The `{paramName}` placeholders are replaced
with `?` before the statement is prepared.

```java
// This is safe — the malicious string is bound as a parameter value, not SQL
store.executeQuery("getUserByStatus", User.class,
    Map.of("status", "active' OR '1'='1"));
// Executes: SELECT * FROM users WHERE status = ?
// With parameter: "active' OR '1'='1" (treated as a literal string)
```

### No XML

SqlStore uses plain `.sql` files. There is no XML parser, eliminating XXE and XML
bomb attack vectors entirely.

### No Unsafe Deserialization

Result mapping uses field-level reflection only. There is no `ObjectInputStream`,
no `Class.forName()`, and no dynamic class loading.

### No External Dependencies

Zero runtime dependencies means zero transitive CVE exposure.

---

## 16. Logging

SqlStore uses `java.util.logging` (JUL) — the JDK's built-in logging framework.

### Logger Names

| Logger | Class |
|--------|-------|
| `no.skaperiet.sqlstore.SqlStore` | Main class |
| `no.skaperiet.sqlstore.query.QueryRegistry` | Query loading |

### Log Levels

| Level | What is logged |
|-------|---------------|
| `WARNING` | Query not found for given name + params, connection close errors |
| `FINE` | SQL text being executed, parameter values, loaded query names |

### Configuration

Since JUL is used, configure via `logging.properties`, programmatic API, or your
framework's JUL bridge (e.g., `jul-to-slf4j` for SLF4J):

```properties
# Example: enable FINE logging for SqlStore
no.skaperiet.sqlstore.level=FINE
```

---

## 17. Error Handling

### Exceptions

| Method | Exception | Condition |
|--------|-----------|-----------|
| Constructor | `NullPointerException` | `dataSource` or `jdbcUrl` is null |
| Constructor | `IOException` | Query file/directory cannot be read |
| `executeQuery` | `SQLException` | Database error during query execution or result mapping |
| `executeQueryForId` | `SQLException` | Database error, or more than one row returned |
| `executeUpdate` | `SQLException` | Database error during execution |
| `executeGenericQuery` | `SQLException` | Database error, or missing named parameter |
| `executeGenericUpdate` | `SQLException` | Database error, or missing named parameter |

### Missing Named Parameter

If the SQL string references `{paramName}` but the caller's map does not contain
`paramName` as a key, a `SQLException` is thrown:

```
SQLException: Missing value for named parameter: {status}
```

### Unknown Query

When a named query is not found in the registry:

| Method | Behavior |
|--------|----------|
| `executeQuery` | Returns empty list, logs warning |
| `executeUpdate` | Returns `null`, logs warning |

This is a soft failure — no exception is thrown for missing queries.

### Result Mapping Failure

If a row cannot be mapped to the target class (e.g., no no-arg constructor, inaccessible
fields, constructor mismatch for records), a `SQLException` is thrown wrapping the
underlying `ReflectiveOperationException`.

---

## 18. Complete Usage Examples

### Basic Setup

```sql
-- file: queries/users.sql

-- :name getAllUsers
SELECT * FROM users

-- :name getUserById
SELECT * FROM users WHERE id = {id}

-- :name insertUser
INSERT INTO users (name, email) VALUES ({name}, {email})

-- :name updateEmail
UPDATE users SET email = {email} WHERE id = {id}

-- :name deleteUser
DELETE FROM users WHERE id = {id}
```

### POJO Usage

```java
public class User {
    public int id;
    public String name;
    public String email;
}

try (var store = new SqlStore("jdbc:postgresql://localhost/mydb", Path.of("queries/"))) {

    // Insert
    store.executeUpdate("insertUser", Map.of("name", "Alice", "email", "alice@example.com"));

    // Query all
    List<User> users = store.executeQuery("getAllUsers", User.class, Map.of());

    // Query one
    User user = store.executeQueryForId("getUserById", User.class, Map.of("id", 1));

    // Update
    store.executeUpdate("updateEmail", Map.of("id", 1, "email", "new@example.com"));

    // Delete
    store.executeUpdate("deleteUser", Map.of("id", 1));
}
```

### Record Usage

```java
public record User(int id, String name, String email) {}

try (var store = new SqlStore(dataSource, Path.of("queries/"))) {
    List<User> users = store.executeQuery("getAllUsers", User.class, Map.of());
    User user = store.executeQueryForId("getUserById", User.class, Map.of("id", 1));
}
```

### java.time Support

```java
public class Event {
    public int id;
    public String title;
    public LocalDate eventDate;       // mapped from DATE column
    public LocalDateTime createdAt;   // mapped from TIMESTAMP column
}

List<Event> events = store.executeQuery("getEvents", Event.class, Map.of());
```

### Generic Queries

```java
// Ad-hoc SELECT — returns List<Map<String, Object>>
List<Map<String, Object>> rows = store.executeGenericQuery(
    "SELECT name, COUNT(*) AS cnt FROM users GROUP BY name",
    Map.of());

for (Map<String, Object> row : rows) {
    System.out.println(row.get("name") + ": " + row.get("cnt"));
}

// Ad-hoc UPDATE — returns row count
int affected = store.executeGenericUpdate(
    "UPDATE users SET status = {status} WHERE score < {minScore}",
    Map.of("status", "inactive", "minScore", 50.0));
```

### DataSource with Connection Pool

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:postgresql://localhost/mydb");
config.setUsername("user");
config.setPassword("pass");
DataSource ds = new HikariDataSource(config);

try (var store = new SqlStore(ds, Path.of("queries/"))) {
    // Each operation gets a fresh connection from the pool
    List<User> users = store.executeQuery("getAllUsers", User.class, Map.of());
}
```

### Null Parameters

`Map.of()` does not allow null values. Use `HashMap` for nullable parameters:

```java
Map<String, Object> params = new HashMap<>();
params.put("name", "Alice");
params.put("email", null);    // will be bound as SQL NULL
store.executeUpdate("insertUser", params);
```

---

## 19. Test Suite

The library ships with 83 tests across 4 test classes.

### Test Classes

| Class | Tests | Type | Description |
|-------|-------|------|-------------|
| `SqlStoreTest` | 49 | Integration | Full CRUD with H2, POJO and record mapping, generic queries, type binding, security, edge cases |
| `SqlQueryTest` | 12 | Unit | Parameter extraction, matching, equality, constructors |
| `SqlFileParserTest` | 12 | Unit | Parsing single/multiple queries, multi-line SQL, comments, blank lines, empty content, Windows line endings |
| `QueryRegistryTest` | 10 | Unit | File/directory loading, query lookup, overloaded names, error conditions |

### SqlStoreTest Coverage

| Category | Tests | What is verified |
|----------|-------|-----------------|
| Constructors | 5 | JDBC URL, DataSource, null rejection, non-existent file |
| AutoCloseable | 2 | Double-close safety, try-with-resources |
| executeUpdate | 5 | Insert, update, delete, no-rows-affected, unknown query |
| executeQuery (POJO) | 7 | All rows, filtered, multi-param, empty result, unknown query, int mapping, double mapping |
| executeQueryForId | 3 | Single result, null for no results, throws for multiple |
| executeGenericQuery | 4 | List of maps, empty result, column order, column aliases |
| executeGenericUpdate | 2 | Row count, no-param SQL |
| Parameter binding | 7 | Integer, Long, Double, String, Timestamp, Boolean, null |
| SQL injection | 2 | Injection attempt returns empty, missing param throws |
| Directory loading | 1 | Multiple `.sql` files merged |
| DataSource mode | 1 | Full CRUD via DataSource |
| Sequential operations | 1 | Insert/update/delete/query in sequence |
| Edge cases | 2 | Empty map for parameterless query, no-param generic query |
| Column types | 1 | INT, BIGINT, DOUBLE, REAL, BOOLEAN, VARCHAR, DATE, TIMESTAMP |
| Record mapping | 3 | Single row, multiple rows, queryForId with record |
| java.time mapping | 2 | TIMESTAMP → LocalDateTime, DATE → LocalDate |
| Partial mapping | 1 | POJO with fewer fields than columns |

### Running Tests

```bash
mvn clean test
```

All tests use H2 in-memory database with `DATABASE_TO_LOWER=TRUE` to match
PostgreSQL/MySQL lowercase column behavior.

---

## 20. Internal Architecture

### Data Flow

```
.sql files ──→ SqlFileParser ──→ SqlQuery[] ──→ QueryRegistry
                                                     │
                                    name + params ───→│
                                                     ▼
User call ──→ SqlStore ──→ prepareNamedStatement() ──→ PreparedStatement
                │                                           │
                │                                           ▼
                │                                      ResultSet
                │                                           │
                ▼                                           ▼
           RowMapper.mapRows() ◄────────────────── ResultSetMetaData
                │
                ├── mapRowsToClass()   (POJO path)
                │     └── field resolution (once) + Field.set() (per row)
                │
                └── mapRowsToRecord()  (record path)
                      └── component resolution (once) + canonical constructor (per row)
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| `{param}` over `:param` or `?` | Curly braces are visually distinct and easy to regex-match. They don't conflict with SQL syntax. |
| Field caching in RowMapper | Field resolution (reflection) happens once per query execution, not per row. A 10,000-row result does reflection only once. |
| `LinkedHashMap` for generic queries | Preserves column order from the SQL SELECT clause, making results predictable. |
| Exact param matching | Prevents subtle bugs from accidental extra or missing parameters. |
| Package-private RowMapper | Implementation detail — not part of the public API, free to change between versions. |
| No connection pool built-in | Connection pooling is a solved problem (HikariCP, DBCP). SqlStore accepts a `DataSource`, which is the standard pooling abstraction. |

### SqlQuery Record

```java
public record SqlQuery(String name, String sql, Set<String> parameterNames) {
    public SqlQuery(String name, String sql) {
        this(name, sql, extractParamNames(sql));
    }
    public boolean matches(Set<String> callerParamNames) {
        return parameterNames.equals(callerParamNames);
    }
}
```

- Immutable by design (Java record)
- Parameter names are auto-extracted from SQL at construction time
- `matches()` provides the parameter-signature-based lookup

### QueryRegistry Internals

```java
Map<String, List<SqlQuery>> queriesByName
```

- Queries are indexed by name for O(1) name lookup
- Each name maps to a list of candidates (for overloaded queries)
- Linear scan within the candidate list for parameter matching (typically 1-3 candidates)

### prepareNamedStatement Flow

1. Regex `\{(\w+)\}` scans the SQL string
2. Each match is replaced with `?` and the value is added to an ordered list
3. The modified SQL is passed to `conn.prepareStatement()`
4. Values are bound in order via `bindParameter()`
5. The `PreparedStatement` is returned to the caller
