# NEW_SPEC.md — XMLDB v0.9 Migration Specification

Specification for migrating XMLDB from commit `4f3f4e2` (v0.5, Java 8) to v0.9 (Java 17). This document describes every change required to transform the library into a modern, secure, SQL-injection-proof query library with `.sql` file support and a `Map.of()` API.

---

## Table of Contents

1. [Goals](#1-goals)
2. [Non-Goals](#2-non-goals)
3. [pom.xml Changes](#3-pomxml-changes)
4. [Query Storage: XML to .sql Files](#4-query-storage-xml-to-sql-files)
5. [Class-by-Class Changes](#5-class-by-class-changes)
   - 5.1 [Remove: Parameter](#51-remove-parameter)
   - 5.2 [Remove: Query (rewrite)](#52-remove-query-rewrite-as-sqlquery-record)
   - 5.3 [Remove: QueryTree (rewrite)](#53-remove-querytree-rewrite-as-queryregistry)
   - 5.4 [Remove: XmlDbPopulatedObject](#54-remove-xmldbpopulatedobject)
   - 5.5 [Rewrite: XmlDB](#55-rewrite-xmldb)
6. [Security Fixes](#6-security-fixes)
7. [API Surface — Before and After](#7-api-surface--before-and-after)
8. [Migration Guide for Callers](#8-migration-guide-for-callers)
9. [File Tree — Before and After](#9-file-tree--before-and-after)
10. [Library Renaming](#10-library-renaming)
11. [Checklist](#11-checklist)

---

## 1. Goals

- **Java 17** — use records, sealed classes, switch expressions, pattern matching, `Map.of()`, text blocks
- **Named parameters only** — drop `?` positional placeholders; all SQL uses `{paramName}` notation
- **`Map.of()` API** — callers pass `Map<String, Object>` instead of `Parameter` objects
- **`.sql` file storage** — replace XML with plain `.sql` files using `-- :name` comment convention
- **No `Parameter` class** — eliminate entirely; parameters are `Map` entries
- **Security hardened** — XXE protection (for any remaining XML parsing), SQL injection proof, no plaintext credentials in URLs, proper resource management
- **Updated dependencies** — remove log4j 1.2 (EOL, CVE-2021-44228 family), update Gson, update Maven plugins
- **AutoCloseable** — `XmlDB implements AutoCloseable` for try-with-resources
- **Database agnostic** — remove hardcoded MySQL JDBC URL format

## 2. Non-Goals

- Changing the package name (`no.skaperiet.xmldb` stays)
- Adding an ORM or query builder DSL
- Dropping Gson (it's lightweight and sufficient for ResultSet-to-POJO mapping)
- Adding transaction support (out of scope for this version)

---

## 3. pom.xml Changes

### Current (v0.5)

| Property | Value |
|---|---|
| version | `0.5` |
| Java source/target | `8` |
| maven-compiler-plugin | `3.8.1` |
| log4j | `1.2.13` |
| gson | `2.8.8` |
| Repository URLs | `http://` (insecure) |

### Target (v0.9)

| Property | Value | Reason |
|---|---|---|
| version | `0.9` | New major version |
| Java source/target | `17` | Records, sealed classes, pattern matching, `Map.of()` |
| maven-compiler-plugin | `3.13.0` | Latest stable, Java 17 support |
| log4j | **REMOVE** | EOL, CVE-2021-44228+. Replace with `java.util.logging` (JUL) — zero dependencies |
| gson | `2.11.0` | Latest stable, security fixes |
| Repository URLs | `https://` | Secure transport |

### Concrete pom.xml diff

```xml
<!-- BEFORE -->
<version>0.5</version>

<!-- AFTER -->
<version>0.9</version>
```

```xml
<!-- BEFORE -->
<configuration>
    <source>8</source>
    <target>8</target>
</configuration>

<!-- AFTER -->
<configuration>
    <release>17</release>
    <encoding>UTF-8</encoding>
</configuration>
```

```xml
<!-- BEFORE -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.8.1</version>

<!-- AFTER -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.13.0</version>
```

```xml
<!-- REMOVE entirely -->
<dependency>
    <groupId>log4j</groupId>
    <artifactId>log4j</artifactId>
    <version>1.2.13</version>
</dependency>

<!-- UPDATE -->
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.11.0</version>
</dependency>
```

```xml
<!-- BEFORE -->
<url>http://repo1.maven.org/maven2</url>
<url>http://135.181.145.18:8081/repository/skaperiet-repository/</url>

<!-- AFTER -->
<url>https://repo1.maven.org/maven2</url>
<url>https://135.181.145.18:8081/repository/skaperiet-repository/</url>
```

---

## 4. Query Storage: XML to .sql Files

### Current: XML

```xml
<queries>
    <sqlquery name="getUser">
        <param name="userId" index="1" type="int"/>
        <param name="status" index="2" type="string"/>
        <query>SELECT * FROM users WHERE id = ? AND status = ?</query>
    </sqlquery>
</queries>
```

### Target: `.sql` files with comment-based naming

```sql
-- :name getUser
SELECT * FROM users WHERE id = {userId} AND status = {status}

-- :name getActiveUsers
SELECT * FROM users WHERE status = {status} ORDER BY name

-- :name insertUser
INSERT INTO users (name, email, status) VALUES ({name}, {email}, {status})
```

### Format rules

1. A query starts with `-- :name <queryName>` on its own line
2. All lines following the name declaration, up to the next `-- :name` or end-of-file, are the SQL body
3. Blank lines and SQL comments (`-- ...` not starting with `-- :name`) within the body are preserved as part of the SQL
4. Leading/trailing whitespace is trimmed from the final SQL string
5. Parameter placeholders use `{paramName}` syntax exclusively
6. One `.sql` file may contain multiple queries (multi-query catalog)
7. A directory of `.sql` files is also supported — all files are loaded and merged

### Why `.sql` instead of XML

- No escaping needed for `<`, `>`, `&` in SQL
- SQL syntax highlighting works out-of-the-box in every editor
- Parameter metadata (index, type) is no longer needed — the `Map` API provides values at runtime, and JDBC `setObject` handles type dispatch
- Dramatically less boilerplate per query

---

## 5. Class-by-Class Changes

### 5.1 Remove: `Parameter`

**File:** `src/main/java/no/skaperiet/xmldb/query/Parameter.java`
**Action:** Delete entirely.

**Reason:** With the `Map<String, Object>` API and `{namedParam}` notation, there is no need for a Parameter class. The parameter name is the Map key, the value is the Map value, and positional index is determined by the order `{param}` placeholders appear in the SQL. Type is inferred at runtime via `instanceof` dispatch.

### 5.2 Remove: `Query` — rewrite as `SqlQuery` record

**File:** `src/main/java/no/skaperiet/xmldb/query/Query.java` → **DELETE**
**New file:** `src/main/java/no/skaperiet/xmldb/query/SqlQuery.java`

Replace the mutable `Query` class with a Java 17 record:

```java
package no.skaperiet.xmldb.query;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;

public record SqlQuery(String name, String sql, Set<String> parameterNames) {

    private static final Pattern NAMED_PARAM = Pattern.compile("\\{(\\w+)\\}");

    /**
     * Constructs a SqlQuery by parsing parameter names from {param} placeholders in the SQL.
     */
    public SqlQuery(String name, String sql) {
        this(name, sql, extractParamNames(sql));
    }

    private static Set<String> extractParamNames(String sql) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = NAMED_PARAM.matcher(sql);
        while (m.find()) {
            names.add(m.group(1));
        }
        return Set.copyOf(names);
    }

    /**
     * Returns true if the given set of parameter names matches this query's parameters.
     */
    public boolean matches(Set<String> callerParamNames) {
        return parameterNames.equals(callerParamNames);
    }
}
```

**Key changes from `Query`:**
- Record instead of class (immutable, auto-generates `equals`, `hashCode`, `toString`)
- No DOM dependency — constructed from `(name, sql)` strings parsed from `.sql` files
- No `Vector`, no raw types
- Parameter names extracted automatically from SQL placeholders
- No `index` concept — positional binding is handled at execution time
- `matches(Set<String>)` replaces both `compare(List)` and `compareByNames(Set<String>)`

### 5.3 Remove: `QueryTree` — rewrite as `QueryRegistry`

**File:** `src/main/java/no/skaperiet/xmldb/query/QueryTree.java` → **DELETE**
**New file:** `src/main/java/no/skaperiet/xmldb/query/QueryRegistry.java`

```java
package no.skaperiet.xmldb.query;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class QueryRegistry {

    private static final Logger log = Logger.getLogger(QueryRegistry.class.getName());
    private final Map<String, List<SqlQuery>> queriesByName = new HashMap<>();

    /**
     * Loads queries from a single .sql file or a directory of .sql files.
     */
    public static QueryRegistry load(Path path) throws IOException {
        var registry = new QueryRegistry();
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.list(path)) {
                files.filter(f -> f.toString().endsWith(".sql"))
                     .sorted()
                     .forEach(f -> {
                         try {
                             registry.loadFile(f);
                         } catch (IOException e) {
                             throw new RuntimeException("Failed to load query file: " + f, e);
                         }
                     });
            }
        } else {
            registry.loadFile(path);
        }
        return registry;
    }

    private void loadFile(Path file) throws IOException {
        String content = Files.readString(file);
        List<SqlQuery> parsed = SqlFileParser.parse(content);
        for (SqlQuery q : parsed) {
            queriesByName.computeIfAbsent(q.name(), k -> new ArrayList<>()).add(q);
            log.fine("Loaded query: " + q.name() + " params=" + q.parameterNames());
        }
    }

    /**
     * Finds a query by name and matching parameter names.
     * Returns null if no match found.
     */
    public SqlQuery findQuery(String name, Set<String> paramNames) {
        List<SqlQuery> candidates = queriesByName.get(name);
        if (candidates == null) return null;
        for (SqlQuery q : candidates) {
            if (q.matches(paramNames)) {
                return q;
            }
        }
        return null;
    }
}
```

**Key changes from `QueryTree`:**
- No XML parsing — reads plain `.sql` files via `Files.readString()`
- No `DocumentBuilder`, no XXE attack surface at all
- Uses `java.util.logging` instead of log4j
- Stores queries in a `Map<String, List<SqlQuery>>` for O(1) name lookup (was O(n) iteration over HashSet)
- `load(Path)` static factory instead of constructor-based side effects
- Proper exception propagation (no swallowed exceptions returning `null`)

### New: `SqlFileParser`

**New file:** `src/main/java/no/skaperiet/xmldb/query/SqlFileParser.java`

```java
package no.skaperiet.xmldb.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses .sql files with -- :name convention into SqlQuery objects.
 */
public final class SqlFileParser {

    private static final String NAME_PREFIX = "-- :name ";

    private SqlFileParser() {}

    public static List<SqlQuery> parse(String content) {
        List<SqlQuery> queries = new ArrayList<>();
        String[] lines = content.split("\n");
        String currentName = null;
        StringBuilder currentSql = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(NAME_PREFIX)) {
                // Save previous query if exists
                if (currentName != null) {
                    queries.add(new SqlQuery(currentName, currentSql.toString().trim()));
                }
                currentName = trimmed.substring(NAME_PREFIX.length()).trim();
                currentSql = new StringBuilder();
            } else if (currentName != null) {
                if (currentSql.length() > 0) {
                    currentSql.append("\n");
                }
                currentSql.append(line);
            }
        }

        // Don't forget the last query
        if (currentName != null && currentSql.length() > 0) {
            queries.add(new SqlQuery(currentName, currentSql.toString().trim()));
        }

        return queries;
    }
}
```

### 5.4 Remove: `XmlDbPopulatedObject`

**File:** `src/main/java/no/skaperiet/xmldb/XmlDbPopulatedObject.java`
**Action:** Delete entirely.

**Reason:** This interface was never used internally, and the Gson-based POJO mapping (`executeQuery(name, clazz, params)`) provides a better approach. If callers need custom ResultSet mapping, they can use `executeGenericQuery` and process the `JsonObject` result.

### 5.5 Rewrite: `XmlDB`

**File:** `src/main/java/no/skaperiet/xmldb/XmlDB.java`

This is the largest change. The table below summarizes what stays, what changes, and what is removed.

#### What is REMOVED

| Feature | Reason |
|---|---|
| `Parameter` imports and all `Parameter`-based method signatures | Replaced by `Map<String, Object>` |
| Cursor-style API: `next()`, `getString()`, `getInt()`, `getDate()`, `getDouble()`, `getLong()`, `getResultSet()`, `numRows()`, `resetPosition()` | Stateful, error-prone. Replaced by POJO mapping and `executeGenericQuery` |
| Instance field `ResultSet rs` | No longer stored as state |
| Instance field `Statement stmt` | Never used correctly |
| Instance field `boolean hasResultSet` | Cursor API removed |
| `setParameters(PreparedStatement, List<Parameter>)` | No more `Parameter` class |
| `prepareStatement(String, List<Parameter>)` | No more dual notation; `{param}` only |
| `getNodeValue(Element, String)` | XML helper, no longer needed |
| Hardcoded MySQL URL: `jdbc:mysql://...` | Database-agnostic now |
| `Class.forName(driver).newInstance()` | Deprecated in Java 9+; JDBC 4.0+ auto-discovers drivers |
| System property `no.skaperiet.xmldb.driverClassFile` | No longer needed with JDBC 4.0+ |
| `configFileName` field and accessors | Unused |
| log4j imports and Logger | Replaced with `java.util.logging` |

#### What is ADDED

| Feature | Description |
|---|---|
| `implements AutoCloseable` | Enables try-with-resources |
| `close()` method | Closes the connection |
| Constructor: `XmlDB(DataSource, Path)` | Preferred constructor — no credentials, connection-pool-ready |
| Constructor: `XmlDB(String jdbcUrl, Path)` | For direct connections — accepts a full JDBC URL (any database) |
| `QueryRegistry` field | Replaces `QueryTree tree` |
| Type dispatch via switch expression | Replaces `if/else instanceof` chain |

#### Constructor changes

**Before (v0.5):**
```java
public XmlDB(String dbName, String dbHost, String dbUser, String dbPass, String dbQueryFile)
```

**After (v0.9) — two constructors:**

```java
// Preferred: DataSource-based (for connection pools, JNDI, testing)
public XmlDB(DataSource dataSource, Path queryPath) throws IOException {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.jdbcUrl = null;
    this.registry = QueryRegistry.load(queryPath);
}

// Direct JDBC: accepts full URL like "jdbc:postgresql://host/db?user=x&password=y"
public XmlDB(String jdbcUrl, Path queryPath) throws IOException {
    this.dataSource = null;
    this.jdbcUrl = Objects.requireNonNull(jdbcUrl);
    this.registry = QueryRegistry.load(queryPath);
}
```

Key differences:
- No immediate `connectDB()` call — connection is lazy (obtained when first query executes)
- No hardcoded MySQL URL format — any JDBC URL works
- `Path` instead of `String` for query file/directory
- `IOException` is thrown, not swallowed

#### Connection management

```java
private Connection getConnection() throws SQLException {
    if (dataSource != null) {
        return dataSource.getConnection();
    } else {
        return DriverManager.getConnection(jdbcUrl);
    }
}
```

- No stored `Connection conn` field in the pooled case — a fresh connection per operation (returned to pool in `finally`)
- For the direct-JDBC case, a single connection is cached and reused (re-created if closed)
- `AutoCloseable.close()` closes the cached connection if one exists

#### Public API methods (v0.9)

```java
// SELECT → List of POJOs
public <T> List<T> executeQuery(String name, Class<T> clazz, Map<String, Object> params)
    throws SQLException

// SELECT → single POJO or null (throws if >1 row)
public <T> T executeQueryForId(String name, Class<T> clazz, Map<String, Object> params)
    throws SQLException

// INSERT/UPDATE/DELETE → true if rows affected, false if 0, null if query not found
public Boolean executeUpdate(String name, Map<String, Object> params)
    throws SQLException

// Ad-hoc SELECT with named params → JsonObject with "rows" and "columns"
public JsonObject executeGenericQuery(String sql, Map<String, Object> params)
    throws SQLException

// Ad-hoc INSERT/UPDATE/DELETE with named params → row count
public int executeGenericUpdate(String sql, Map<String, Object> params)
    throws SQLException

// AutoCloseable
public void close()
```

**That's it.** Six public methods. No cursor API. No `Parameter` objects.

#### Parameter binding — switch expression

Replace the `if/else instanceof` chain with a Java 17 switch expression with pattern matching:

```java
private void bindParameter(PreparedStatement stmt, int index, Object value) throws SQLException {
    switch (value) {
        case Integer i  -> stmt.setInt(index, i);
        case Long l     -> stmt.setLong(index, l);
        case Double d   -> stmt.setDouble(index, d);
        case Float f    -> stmt.setFloat(index, f);
        case Boolean b  -> stmt.setBoolean(index, b);
        case Timestamp t -> stmt.setTimestamp(index, t);
        case String s   -> stmt.setString(index, s);
        case null       -> stmt.setNull(index, java.sql.Types.NULL);
        default         -> stmt.setObject(index, value);
    }
}
```

> Note: Pattern matching in switch with type patterns requires Java 21 for finalized syntax. For Java 17, use `instanceof` checks in a cleaner extracted method or use preview features. A safe Java 17 implementation:

```java
private void bindParameter(PreparedStatement stmt, int index, Object value) throws SQLException {
    if (value == null)               { stmt.setNull(index, java.sql.Types.NULL); }
    else if (value instanceof Integer v)   { stmt.setInt(index, v); }
    else if (value instanceof Long v)      { stmt.setLong(index, v); }
    else if (value instanceof Double v)    { stmt.setDouble(index, v); }
    else if (value instanceof Float v)     { stmt.setFloat(index, v); }
    else if (value instanceof Boolean v)   { stmt.setBoolean(index, v); }
    else if (value instanceof Timestamp v) { stmt.setTimestamp(index, v); }
    else if (value instanceof String v)    { stmt.setString(index, v); }
    else                                   { stmt.setObject(index, value); }
}
```

> Java 17 `instanceof` pattern matching (`value instanceof Integer v`) is a finalized feature and requires no preview flags.

#### Resource management

All query execution methods must use try-with-resources:

```java
public <T> List<T> executeQuery(String name, Class<T> clazz, Map<String, Object> params)
        throws SQLException {
    SqlQuery query = registry.findQuery(name, params.keySet());
    if (query == null) {
        log.warning("No query matching name and params: " + name + " " + params.keySet());
        return List.of();
    }

    try (Connection conn = getConnection();
         PreparedStatement stmt = prepareNamedStatement(conn, query.sql(), params);
         ResultSet rs = stmt.executeQuery()) {

        List<T> results = new ArrayList<>();
        Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();
        while (rs.next()) {
            JsonObject json = convertResultSetToJson(rs);
            T obj = gson.fromJson(json, clazz);
            if (obj != null) {
                results.add(obj);
            }
        }
        return results;
    }
}
```

#### `prepareNamedStatement` — now takes a `Connection`

```java
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
```

#### `convertResultSetToJson` — use switch expression

The existing `if/else` chain for JDBC types becomes a switch expression (this is `int` switching, which is fully supported in Java 17):

```java
private JsonObject convertResultSetToJson(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    int columnCount = meta.getColumnCount();
    JsonObject obj = new JsonObject();

    for (int i = 1; i <= columnCount; i++) {
        String name = meta.getColumnLabel(i);
        if (name == null || name.isEmpty()) {
            name = meta.getColumnName(i);
        }

        switch (meta.getColumnType(i)) {
            case Types.BIGINT       -> obj.addProperty(name, rs.getLong(i));
            case Types.REAL         -> obj.addProperty(name, rs.getFloat(i));
            case Types.BOOLEAN,
                 Types.BIT          -> obj.addProperty(name, rs.getBoolean(i));
            case Types.DOUBLE,
                 Types.FLOAT        -> obj.addProperty(name, rs.getDouble(i));
            case Types.INTEGER      -> obj.addProperty(name, rs.getInt(i));
            case Types.TINYINT      -> obj.addProperty(name, rs.getByte(i));
            case Types.SMALLINT     -> obj.addProperty(name, rs.getShort(i));
            case Types.NUMERIC,
                 Types.DECIMAL      -> obj.addProperty(name, rs.getBigDecimal(i));
            case Types.NVARCHAR,
                 Types.NCHAR,
                 Types.LONGNVARCHAR -> obj.addProperty(name, rs.getNString(i));
            case Types.DATE         -> {
                var d = rs.getDate(i);
                obj.addProperty(name, d != null ? d.toString() : null);
            }
            case Types.TIMESTAMP    -> {
                var t = rs.getTimestamp(i);
                obj.addProperty(name, t != null ? t.toString() : null);
            }
            case Types.TIME         -> {
                var t = rs.getTime(i);
                obj.addProperty(name, t != null ? t.toString() : null);
            }
            default                 -> obj.addProperty(name, rs.getString(i));
        }
    }
    return obj;
}
```

---

## 6. Security Fixes

### 6.1 XXE Injection — ELIMINATED

**Before:** `QueryTree.openDocument()` uses `DocumentBuilderFactory` with default settings, allowing XXE attacks.

**After:** XML parsing is completely removed. `.sql` files are plain text read via `Files.readString()`. There is no XML parser, so there is no XXE attack surface.

### 6.2 SQL Injection — ELIMINATED

**Before:** v0.5 had `executeGenericQuery(String sql)` that accepted raw SQL. Even the `?` placeholder system allowed callers to construct SQL by string concatenation.

**After:**
- All SQL execution goes through `PreparedStatement` with `?` bind variables
- Named `{param}` placeholders are replaced with `?` before preparing the statement
- `prepareNamedStatement` throws `SQLException` if a placeholder references a missing parameter name
- No raw SQL execution methods exist

### 6.3 Plaintext Credentials in JDBC URL — MITIGATED

**Before:** `connectDB()` constructs: `"jdbc:mysql://" + host + "/" + db + "?user=" + user + "&password=" + pass`

**After:**
- The `DataSource` constructor (preferred) accepts no credentials — they are configured on the DataSource by the container/pool
- The `String jdbcUrl` constructor accepts a full URL provided by the caller — the library does not construct URLs from parts
- Credentials are never stored as separate fields in the `XmlDB` object

### 6.4 Arbitrary Class Instantiation — ELIMINATED

**Before:** `Class.forName(driver).newInstance()` loads whatever class is in the `no.skaperiet.xmldb.driverClassFile` system property.

**After:** Removed entirely. JDBC 4.0+ (Java 6+) auto-discovers drivers via `ServiceLoader`. `DriverManager.getConnection(url)` works without loading driver classes explicitly.

### 6.5 Vulnerable Dependencies — FIXED

| Dependency | Before | After | Issue |
|---|---|---|---|
| log4j | 1.2.13 | **REMOVED** | CVE-2019-17571, CVE-2021-4104, EOL |
| Gson | 2.8.8 | 2.11.0 | Multiple fixes, improved security |

Replaced log4j with `java.util.logging` (JUL) — zero additional dependencies.

### 6.6 Insecure Repository URLs — FIXED

**Before:** `http://repo1.maven.org/maven2` and `http://135.181.145.18:8081/...`

**After:** All repository URLs use `https://`.

### 6.7 Resource Leaks — FIXED

**Before:** ResultSet and Statement objects are often not closed, or closed in empty catch blocks. Connection is never closed in pooled mode after use.

**After:** All database resources use try-with-resources:
```java
try (Connection conn = getConnection();
     PreparedStatement stmt = prepareNamedStatement(conn, sql, params);
     ResultSet rs = stmt.executeQuery()) {
    // process results
}
```

### 6.8 Exception Swallowing — FIXED

**Before:** Multiple `catch (Exception e) {}` blocks that silently discard errors. `openDocument()` returns `null` on parse failure.

**After:** Exceptions propagate to callers. `IOException` on file loading. `SQLException` on database operations. No silent `null` returns.

---

## 7. API Surface — Before and After

### Before (v0.5) — Caller code

```java
// Construction
XmlDB db = new XmlDB("mydb", "localhost", "root", "secret", "/path/to/queries.xml");

// Query with Parameter objects
db.executeQuery("getUser",
    new Parameter(1, "userId", 42),
    new Parameter(2, "status", "active"));
while (db.next()) {
    String name = db.getString("name");
    int age = db.getInt("age");
}

// Query with POJO mapping
List<User> users = db.executeQuery("getUser", User.class,
    new Parameter(1, "userId", 42),
    new Parameter(2, "status", "active"));

// Generic query (SQL injection risk!)
db.executeGenericQuery("SELECT * FROM users WHERE id = " + userId);
```

### After (v0.9) — Caller code

```java
// Construction — DataSource (preferred)
try (var db = new XmlDB(dataSource, Path.of("/path/to/queries"))) {

    // Query with Map.of()
    List<User> users = db.executeQuery("getUser", User.class,
        Map.of("userId", 42, "status", "active"));

    // Single result
    User user = db.executeQueryForId("getUser", User.class,
        Map.of("userId", 42, "status", "active"));

    // Update
    db.executeUpdate("deactivateUser",
        Map.of("userId", 42));

    // Generic query (safe)
    JsonObject result = db.executeGenericQuery(
        "SELECT * FROM users WHERE id = {userId}",
        Map.of("userId", 42));
}

// Construction — direct JDBC URL
try (var db = new XmlDB("jdbc:postgresql://host/db?user=x&password=y",
                         Path.of("/path/to/queries.sql"))) {
    // same API
}
```

---

## 8. Migration Guide for Callers

### Step 1: Convert XML query files to `.sql`

**Before** (`queries.xml`):
```xml
<queries>
    <sqlquery name="getUser">
        <param name="userId" index="1" type="int"/>
        <query>SELECT * FROM users WHERE id = ?</query>
    </sqlquery>
    <sqlquery name="insertUser">
        <param name="name" index="1" type="string"/>
        <param name="email" index="2" type="string"/>
        <query>INSERT INTO users (name, email) VALUES (?, ?)</query>
    </sqlquery>
</queries>
```

**After** (`queries.sql`):
```sql
-- :name getUser
SELECT * FROM users WHERE id = {userId}

-- :name insertUser
INSERT INTO users (name, email) VALUES ({name}, {email})
```

### Step 2: Update constructor

```java
// Before
XmlDB db = new XmlDB("mydb", "localhost", "user", "pass", "/path/queries.xml");

// After (DataSource — recommended)
XmlDB db = new XmlDB(dataSource, Path.of("/path/queries.sql"));

// After (direct URL)
XmlDB db = new XmlDB("jdbc:mysql://localhost/mydb?user=user&password=pass",
                       Path.of("/path/queries.sql"));
```

### Step 3: Replace `Parameter` with `Map.of()`

```java
// Before
db.executeQuery("getUser", User.class,
    new Parameter(1, "userId", userId),
    new Parameter(2, "status", status));

// After
db.executeQuery("getUser", User.class,
    Map.of("userId", userId, "status", status));
```

### Step 4: Replace cursor API with POJO mapping

```java
// Before
db.executeQuery("getUsers", new Parameter(1, "status", "active"));
while (db.next()) {
    String name = db.getString("name");
    int age = db.getInt("age");
}

// After
List<User> users = db.executeQuery("getUsers", User.class,
    Map.of("status", "active"));
for (User user : users) {
    String name = user.getName();
    int age = user.getAge();
}
```

### Step 5: Wrap in try-with-resources

```java
// Before
XmlDB db = new XmlDB(...);
// ... use db ...
db.closeConnection();

// After
try (var db = new XmlDB(dataSource, Path.of("queries.sql"))) {
    // ... use db ...
} // auto-closed
```

### Step 6: Replace log4j configuration

Since v0.9 uses `java.util.logging`, callers can configure logging via:
- `logging.properties` file
- `java.util.logging.config.file` system property
- Programmatic configuration via `LogManager`
- SLF4J bridge if the application uses SLF4J

---

## 9. File Tree — Before and After

### Before

```
src/main/java/no/skaperiet/xmldb/
├── XmlDB.java
├── XmlDbPopulatedObject.java
└── query/
    ├── Parameter.java
    ├── Query.java
    └── QueryTree.java
```

### After

```
src/main/java/no/skaperiet/xmldb/
├── XmlDB.java                    (rewritten)
└── query/
    ├── SqlQuery.java             (new — record, replaces Query + Parameter)
    ├── QueryRegistry.java        (new — replaces QueryTree)
    └── SqlFileParser.java        (new — .sql file parser)
```

**Deleted files:**
- `Parameter.java`
- `Query.java`
- `QueryTree.java`
- `XmlDbPopulatedObject.java`

---

## 10. Library Renaming

With XML gone and `.sql` files as the query storage format, the name **XMLDB** no longer describes what the library does. It actually creates confusion — a new developer seeing "XMLDB" would expect an XML database, not a SQL query-by-name library. A good name should communicate the core value proposition: *externalized, named SQL queries executed safely*.

### Naming criteria

A replacement name should:

1. **Describe what it does** — named SQL query execution
2. **Be short** — easy to type, easy to remember, works well as a class name
3. **Not collide** — not already a well-known Java library or Maven artifact
4. **Work as a Java identifier** — valid class name, no hyphens in the package

### Candidates

| Name | Class Name | Artifact ID | Rationale |
|---|---|---|---|
| **SqlStore** | `SqlStore` | `sqlstore` | The library is a "store" of SQL queries you execute by name. Simple, descriptive, unpretentious. |
| **NamedSQL** | `NamedSql` | `namedsql` | Directly says what it is: SQL queries identified by name. Clear and searchable. |
| **QueryVault** | `QueryVault` | `queryvault` | Queries are stored in a "vault" (the `.sql` files) and retrieved by name. Slightly more evocative. |
| **SqlCatalog** | `SqlCatalog` | `sqlcatalog` | A catalog of SQL queries. Emphasizes the registry/lookup nature. |
| **JustSQL** | `JustSql` | `justsql` | Conveys the philosophy: just write SQL, nothing more. No ORM, no magic. |
| **SqlBox** | `SqlBox` | `sqlbox` | Short, memorable. A "box" you put your queries in and pull them out by name. (Note: a Python library called `sqlbox` exists, but no Java conflict.) |

### Recommendation: **SqlStore**

`SqlStore` is the strongest candidate:

- **Accurate** — the library stores SQL queries and executes them. That's it.
- **Two syllables** — shorter than every alternative except SqlBox
- **Intuitive API** — `SqlStore db = new SqlStore(dataSource, Path.of("queries/"))` reads naturally
- **No namespace collision** — no existing Java library uses this name on Maven Central
- **Works everywhere** — as a class name (`SqlStore`), artifact ID (`sqlstore`), package segment, and in conversation ("we use SqlStore for database access")

### What changes with a rename

If the rename is adopted, these additional changes apply on top of the v0.9 spec:

| Item | Before | After |
|---|---|---|
| Artifact ID | `xmldb` | `sqlstore` |
| Root package | `no.skaperiet.xmldb` | `no.skaperiet.sqlstore` |
| Main class | `XmlDB` | `SqlStore` |
| pom.xml `<name>` | `XMLDB` | `SqlStore` |
| Query subpackage | `no.skaperiet.xmldb.query` | `no.skaperiet.sqlstore.query` |
| Repository/directory name | `XMLDB` | `sqlstore` |

The rename is a one-time mechanical change (find-and-replace across 4 source files and `pom.xml`). It could be done in v0.9 or deferred to v1.0 — but doing it now, when the entire codebase is being rewritten anyway, is the lowest-cost moment.

### Alternative: Keep `XmlDB` as a deprecated alias

If backwards compatibility matters for existing callers, a thin wrapper can bridge the gap:

```java
@Deprecated(since = "0.9", forRemoval = true)
public class XmlDB extends SqlStore {
    public XmlDB(DataSource ds, Path queryPath) throws IOException {
        super(ds, queryPath);
    }
    public XmlDB(String jdbcUrl, Path queryPath) throws IOException {
        super(jdbcUrl, queryPath);
    }
}
```

This lets existing code compile while encouraging migration to the new name.

---

## 11. Checklist

Implementation tasks in dependency order:

- [ ] **pom.xml** — update version to 0.9, Java 17, remove log4j, update Gson to 2.11.0, update maven-compiler-plugin to 3.13.0, switch repository URLs to HTTPS
- [ ] **SqlQuery.java** — create record with `(name, sql, parameterNames)`, `matches()` method
- [ ] **SqlFileParser.java** — create `.sql` file parser with `-- :name` convention
- [ ] **QueryRegistry.java** — create registry that loads `.sql` files/directories, `findQuery()` method
- [ ] **Delete Parameter.java** — remove entirely
- [ ] **Delete Query.java** — replaced by SqlQuery
- [ ] **Delete QueryTree.java** — replaced by QueryRegistry
- [ ] **Delete XmlDbPopulatedObject.java** — unused interface
- [ ] **XmlDB.java** — full rewrite:
  - [ ] Replace log4j with `java.util.logging`
  - [ ] Add `implements AutoCloseable`
  - [ ] New constructors: `XmlDB(DataSource, Path)` and `XmlDB(String jdbcUrl, Path)`
  - [ ] Remove old constructor and all credential fields
  - [ ] Remove cursor API (`next`, `getString`, `getInt`, `getDate`, `getDouble`, `getLong`, `getResultSet`, `numRows`, `resetPosition`)
  - [ ] Remove `ResultSet rs`, `Statement stmt`, `hasResultSet` fields
  - [ ] Remove `setParameters()`, old `prepareStatement()`
  - [ ] Remove `getNodeValue()`, `configFileName`
  - [ ] Remove `Class.forName(driver).newInstance()`
  - [ ] Remove hardcoded MySQL URL construction
  - [ ] Update `prepareNamedStatement` to accept `Connection` parameter
  - [ ] Add `bindParameter()` with `instanceof` pattern matching
  - [ ] Update `convertResultSetToJson` to use switch expression
  - [ ] Update all execution methods to use try-with-resources
  - [ ] Keep public API: `executeQuery(name, clazz, Map)`, `executeQueryForId(name, clazz, Map)`, `executeUpdate(name, Map)`, `executeGenericQuery(sql, Map)`, `executeGenericUpdate(sql, Map)`, `close()`
- [ ] **Convert test XML files** (if any) to `.sql` format
- [ ] **Rename library** (optional, recommended) — rename artifact to `sqlstore`, package to `no.skaperiet.sqlstore`, main class to `SqlStore`; optionally keep `XmlDB` as a deprecated alias
- [ ] **Build and verify** — `mvn clean compile` succeeds with Java 17
