# SqlStore

A small, zero-dependency Java 17 library for executing externalized SQL queries with named parameters and automatic result mapping.

**Keep your SQL in `.sql` files. Keep your Java clean.**

```sql
-- queries/users.sql

-- :name getUserById
SELECT * FROM users WHERE id = {id}

-- :name insertUser
INSERT INTO users (name, email) VALUES ({name}, {email})
```

```java
try (var store = new SqlStore("jdbc:postgresql://localhost/mydb", Path.of("queries/"))) {
    User user = store.executeQueryForId("getUserById", User.class, Map.of("id", 1));
    store.executeUpdate("insertUser", Map.of("name", "Alice", "email", "alice@example.com"));
}
```

## Installation

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>no.skaperiet.sqlstore</groupId>
    <artifactId>sqlstore</artifactId>
    <version>1.0</version>
</dependency>
```

Requires Java 17 or higher. No transitive dependencies.

## Writing SQL Files

Define your queries in plain `.sql` files using `-- :name` to name each query, and `{param}` for parameters:

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

-- :name updateStatus
UPDATE users SET status = {status} WHERE id = {id}

-- :name deleteUser
DELETE FROM users WHERE id = {id}
```

Queries can span multiple lines. SQL comments inside query bodies are preserved. Multiple queries with the same name are allowed as long as their parameter sets differ.

You can organize queries across multiple files in a directory:

```
queries/
  users.sql
  orders.sql
  products.sql
```

## Creating a SqlStore

### With a JDBC URL (simple usage)

```java
var store = new SqlStore("jdbc:h2:mem:mydb", Path.of("queries/users.sql"));
```

A single connection is created and reused. Good for scripts, CLI tools, and tests.

### With a DataSource (production usage)

```java
var store = new SqlStore(dataSource, Path.of("queries/"));
```

A fresh connection is obtained from the pool for each operation. Use this with HikariCP, DBCP, or any connection pool.

### Loading queries

Pass a single `.sql` file or a directory. When given a directory, all `.sql` files in it are loaded and merged.

```java
// Single file
new SqlStore(jdbcUrl, Path.of("queries.sql"));

// Directory (all .sql files inside)
new SqlStore(jdbcUrl, Path.of("queries/"));
```

## Querying

### Map results to a class

```java
public class User {
    public int id;
    public String name;
    public String email;
}

List<User> users = store.executeQuery("getAllUsers", User.class, Map.of());

List<User> active = store.executeQuery("getUserByStatus", User.class,
    Map.of("status", "active"));
```

Column names are matched to field names. Columns without a matching field are skipped.

### Map results to a record

```java
public record User(int id, String name, String email) {}

List<User> users = store.executeQuery("getAllUsers", User.class, Map.of());
```

Records are instantiated via their canonical constructor. Component names are matched to column names.

### Get a single result

```java
User user = store.executeQueryForId("getUserById", User.class, Map.of("id", 1));
// Returns null if not found
// Throws SQLException if more than one row is returned
```

### Insert, update, delete

```java
Boolean result = store.executeUpdate("insertUser",
    Map.of("name", "Alice", "email", "alice@example.com", "status", "active"));
// true  = rows affected
// false = no rows affected
// null  = query not found
```

### Ad-hoc queries

For one-off SQL that doesn't need a named query:

```java
List<Map<String, Object>> rows = store.executeGenericQuery(
    "SELECT name, COUNT(*) AS cnt FROM users GROUP BY name", Map.of());

int affected = store.executeGenericUpdate(
    "DELETE FROM users WHERE last_login < {cutoff}",
    Map.of("cutoff", Timestamp.valueOf("2024-01-01 00:00:00")));
```

Generic queries return `List<Map<String, Object>>` with column order preserved.

## Supported Types

### Parameter binding

Values in the parameter map are bound to the `PreparedStatement` by their Java type:

`Integer`, `Long`, `Double`, `Float`, `Boolean`, `String`, `Timestamp`, `null`

Other types fall through to `setObject()`.

### Result mapping

Fields are read from the `ResultSet` based on the target field/component type:

| Field Type | Mapped From |
|-----------|-------------|
| `int`, `long`, `double`, `float`, `boolean`, `short`, `byte` | Corresponding JDBC getter |
| `String` | `rs.getString()` |
| `BigDecimal` | `rs.getBigDecimal()` |
| `java.sql.Timestamp`, `Date`, `Time` | Corresponding JDBC getter |
| `LocalDate` | `rs.getDate()` converted via `.toLocalDate()` |
| `LocalDateTime` | `rs.getTimestamp()` converted via `.toLocalDateTime()` |
| `LocalTime` | `rs.getTime()` converted via `.toLocalTime()` |

```java
public class Event {
    public int id;
    public String title;
    public LocalDate eventDate;
    public LocalDateTime createdAt;
}
```

## Null Parameters

`Map.of()` does not accept null values. Use `HashMap` when you need nulls:

```java
var params = new HashMap<String, Object>();
params.put("name", "Alice");
params.put("email", null);
store.executeUpdate("insertUser", params);
```

## Query Overloading

Two queries can share the same name if their parameter sets differ:

```sql
-- :name getUser
SELECT * FROM users WHERE id = {id}

-- :name getUser
SELECT * FROM users WHERE email = {email}
```

```java
store.executeQueryForId("getUser", User.class, Map.of("id", 1));          // uses first
store.executeQueryForId("getUser", User.class, Map.of("email", "a@b.com")); // uses second
```

Resolution is based on an exact match of parameter names. Extra or missing parameters will not match.

## Resource Management

SqlStore implements `AutoCloseable`:

```java
try (var store = new SqlStore(jdbcUrl, Path.of("queries/"))) {
    // use store
} // connection closed automatically
```

## Security

All `{param}` placeholders are replaced with `?` and bound via `PreparedStatement`. Values are never concatenated into the SQL string. This prevents SQL injection by design.

## License

See LICENSE file for details.
