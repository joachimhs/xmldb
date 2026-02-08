# Possible Improvements for SqlStore

A curated list of improvements that respect the library's philosophy: **small, lean, zero-magic**.
Each item is categorized by priority and effort.

---

## Table of Contents

1. [Thread Safety](#1-thread-safety)
2. [Transaction Support](#2-transaction-support)
3. [IN-Clause / Collection Parameters](#3-in-clause--collection-parameters)
4. [Optional Return Types](#4-optional-return-types)
5. [Classpath Resource Loading](#5-classpath-resource-loading)
6. [Query Validation at Load Time](#6-query-validation-at-load-time)
7. [Batch Operations](#7-batch-operations)
8. [executeUpdate Return Type](#8-executeupdate-return-type)
9. [Gson as Optional Dependency](#9-gson-as-optional-dependency)
10. [Query Hot-Reload for Development](#10-query-hot-reload-for-development)
11. [Recursive Directory Loading](#11-recursive-directory-loading)
12. [Generated Key Retrieval](#12-generated-key-retrieval)
13. [Java Module System Support](#13-java-module-system-support)
14. [Publish to Maven Central](#14-publish-to-maven-central)

---

## 1. Thread Safety

**Priority:** High | **Effort:** Low

The `cachedConnection` field in the JDBC URL constructor path is not synchronized. If two threads call `getConnection()` concurrently and both see `cachedConnection == null`, they will each create a separate connection and one will be silently leaked.

**Suggestion:** Use a simple `synchronized` block around the cached connection logic, or use `volatile` with double-checked locking. Since the JDBC URL mode is intended for simple/standalone use, a `synchronized` accessor is the simplest fix:

```java
private synchronized Connection getConnection() throws SQLException {
    // ...existing logic...
}
```

Alternatively, document that the JDBC URL mode is single-threaded only.

---

## 2. Transaction Support

**Priority:** High | **Effort:** Medium

Currently there is no way to run multiple statements within a single transaction. This is a common need for any real application (e.g., transferring funds, creating a user with a profile in two tables).

**Suggestion:** Add a lightweight `inTransaction` method that accepts a lambda:

```java
public <T> T inTransaction(SqlStoreTransaction<T> action) throws SQLException {
    Connection conn = getConnection();
    try {
        conn.setAutoCommit(false);
        T result = action.execute(conn);
        conn.commit();
        return result;
    } catch (Exception e) {
        conn.rollback();
        throw e;
    } finally {
        conn.setAutoCommit(true);
        if (isPooled()) conn.close();
    }
}
```

This keeps the library lean (no AOP, no annotations) while enabling transactional workflows.

---

## 3. IN-Clause / Collection Parameters

**Priority:** Medium | **Effort:** Medium

There is no way to pass a `List` or `Set` as a parameter for SQL `IN (...)` clauses. Users currently have to build these dynamically, which is error-prone.

**Suggestion:** Detect when a parameter value is a `Collection` and expand `{ids}` into `?, ?, ?` with the correct number of placeholders:

```sql
-- :name getUsersByIds
SELECT * FROM users WHERE id IN ({ids})
```

```java
store.executeQuery("getUsersByIds", User.class, Map.of("ids", List.of(1, 2, 3)));
// Expands to: SELECT * FROM users WHERE id IN (?, ?, ?)
```

This is a natural extension of the existing `{param}` syntax and would be very useful in practice.

---

## 4. Optional Return Types

**Priority:** Medium | **Effort:** Low

`executeQueryForId` returns `null` when no row is found. Modern Java (17+) code prefers `Optional<T>` to avoid null-pointer bugs.

**Suggestion:** Add an `Optional`-returning variant:

```java
public <T> Optional<T> findOne(String name, Class<T> clazz, Map<String, Object> params)
        throws SQLException {
    return Optional.ofNullable(executeQueryForId(name, clazz, params));
}
```

This could coexist with the existing `executeQueryForId` for backwards compatibility, or replace it in a major version bump.

---

## 5. Classpath Resource Loading

**Priority:** Medium | **Effort:** Low

Currently queries can only be loaded from filesystem paths. In a packaged JAR, `.sql` files are often on the classpath under `src/main/resources`.

**Suggestion:** Add a static factory or constructor that accepts a classpath resource path:

```java
public SqlStore(DataSource ds, String classpathResource) throws IOException {
    // Use getClass().getResource() or ClassLoader to load from classpath
}
```

This makes the library work seamlessly in Spring Boot, Quarkus, or any JAR-deployed application without unpacking resources to the filesystem.

---

## 6. Query Validation at Load Time

**Priority:** Medium | **Effort:** Low

Invalid `.sql` files are currently silently accepted. For example, a `-- :name` directive with an empty name, or duplicate queries with the same name *and* same parameter signature, produce no warnings.

**Suggestion:** Add validation in `QueryRegistry.load()`:

- Warn or throw on empty query names
- Warn on duplicate name + parameter signature combinations (likely a copy-paste mistake)
- Optionally validate that `{param}` placeholders use valid identifier characters

This helps catch mistakes early during development rather than at runtime.

---

## 7. Batch Operations

**Priority:** Medium | **Effort:** Medium

There is no way to execute a single named query for a batch of parameter sets (e.g., inserting 1000 rows). Users would have to loop and call `executeUpdate` 1000 times, which is very slow.

**Suggestion:** Add a `executeBatch` method:

```java
public int[] executeBatch(String name, List<Map<String, Object>> paramBatch) throws SQLException {
    // Uses JDBC batch API: addBatch() + executeBatch()
}
```

This is a thin wrapper around JDBC's built-in batch support and can yield 10-100x speedup for bulk inserts.

---

## 8. executeUpdate Return Type

**Priority:** Low | **Effort:** Low

`executeUpdate` currently returns `Boolean` (boxed) -- `true` if rows were affected, `null` if query not found. This three-state return (true/false/null) is confusing and easy to misuse.

**Suggestion:** Two options:

1. **Return `int`** (rows affected) and throw an exception for missing queries
2. **Return `OptionalInt`** -- empty for "query not found", present for actual row count

Either approach is clearer than a nullable `Boolean` and gives the caller the actual affected row count, which is often needed.

---

## 9. Gson as Optional Dependency

**Priority:** Low | **Effort:** Medium

Gson is currently the only external runtime dependency. Some users may prefer Jackson, Moshi, or no JSON library at all (just using `Map` results).

**Suggestion:** Consider:

1. A `ResultSetMapper<T>` functional interface that users can implement
2. Ship a default Gson-based mapper
3. Allow the generic methods to return `List<Map<String, Object>>` without needing any JSON library

This would make Gson an optional/provided dependency. However, this adds complexity -- only worthwhile if there is real demand from users. The current Gson approach is simple and works well.

---

## 10. Query Hot-Reload for Development

**Priority:** Low | **Effort:** Medium

During development, changing a `.sql` file requires restarting the application. A reload mechanism would improve the development experience.

**Suggestion:** Add a `reload()` method on `SqlStore` (or on `QueryRegistry`) that re-reads the `.sql` files. This could be called manually or triggered by a file watcher:

```java
store.reload(); // Re-reads all .sql files from the configured path
```

Keep it manual (no built-in file watcher) to stay lean. Frameworks like Spring DevTools or Quarkus dev mode can call `reload()` on file change.

---

## 11. Recursive Directory Loading

**Priority:** Low | **Effort:** Low

`QueryRegistry.load()` only scans the immediate directory -- it does not recurse into subdirectories. Larger projects might organize queries like:

```
queries/
  users/
    find.sql
    create.sql
  orders/
    list.sql
```

**Suggestion:** Use `Files.walk()` instead of `Files.list()` to support nested directory structures. This is a one-line change.

---

## 12. Generated Key Retrieval

**Priority:** Low | **Effort:** Low

After an `INSERT`, applications often need the auto-generated primary key. Currently `executeUpdate` only returns whether rows were affected, not the generated key.

**Suggestion:** Add an `executeInsert` method:

```java
public <T> T executeInsert(String name, Class<T> keyType, Map<String, Object> params)
        throws SQLException {
    // Uses PreparedStatement with RETURN_GENERATED_KEYS
}
```

This is a thin wrapper around JDBC's `getGeneratedKeys()` and avoids the need for a separate SELECT query after each INSERT.

---

## 13. Java Module System Support

**Priority:** Low | **Effort:** Low

Add a `module-info.java` to make the library a proper Java module:

```java
module no.skaperiet.sqlstore {
    requires java.sql;
    requires com.google.gson;
    exports no.skaperiet.sqlstore;
    exports no.skaperiet.sqlstore.query;
}
```

This is forward-looking and costs nothing in terms of complexity. It also enables users who use the module system to have clean dependency declarations.

---

## 14. Publish to Maven Central

**Priority:** Low | **Effort:** Medium

Currently the library is published to a private Nexus repository. Publishing to Maven Central would make it available to anyone:

- Register a group ID with Sonatype (e.g., `io.github.<username>`)
- Add the `maven-gpg-plugin` for signing
- Add the `nexus-staging-maven-plugin` for deployment
- Add `<scm>`, `<developers>`, and `<licenses>` sections to `pom.xml`

This is a one-time setup effort that significantly increases the library's reach.

---

## Summary Matrix

| # | Improvement                   | Priority | Effort | Breaking? |
|---|-------------------------------|----------|--------|-----------|
| 1 | Thread safety                 | High     | Low    | No        |
| 2 | Transaction support           | High     | Medium | No        |
| 3 | IN-clause parameters          | Medium   | Medium | No        |
| 4 | Optional return types         | Medium   | Low    | Optional  |
| 5 | Classpath resource loading    | Medium   | Low    | No        |
| 6 | Query validation at load time | Medium   | Low    | No        |
| 7 | Batch operations              | Medium   | Medium | No        |
| 8 | executeUpdate return type     | Low      | Low    | Yes       |
| 9 | Gson as optional dependency   | Low      | Medium | Yes       |
| 10| Query hot-reload              | Low      | Medium | No        |
| 11| Recursive directory loading   | Low      | Low    | No        |
| 12| Generated key retrieval       | Low      | Low    | No        |
| 13| Java module system support    | Low      | Low    | No        |
| 14| Publish to Maven Central      | Low      | Medium | No        |
