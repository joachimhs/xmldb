# XML_DISCUSSION.md

Alternative approaches to externalizing SQL queries, compared against the current XML format used by XMLDB.

## Current Approach: XML

```xml
<sqlquery name="getActiveUsers">
    <param name="status" index="1" type="string"/>
    <query>SELECT * FROM users WHERE status = ?</query>
</sqlquery>
```

**Strengths:** Schema-validatable, well-supported in Java, parameter metadata is explicit.
**Weaknesses:** Verbose, requires escaping special characters (`<`, `>`, `&`), unfamiliar to newer developers, closing tags add noise.

---

## Alternative 1: YAML

```yaml
queries:
  getActiveUsers:
    params:
      - name: status
        type: string
    sql: >
      SELECT * FROM users
      WHERE status = {status}

  getUserById:
    params:
      - name: id
        type: int
    sql: >
      SELECT * FROM users
      WHERE id = {id}
```

**Strengths:**
- Significantly less visual noise than XML
- Multi-line strings are natural with `>` or `|` block scalars
- No escaping needed for SQL operators like `<` and `>`
- Widely adopted in modern tooling (Kubernetes, CI/CD, Spring Boot)
- Most Java developers encounter YAML daily

**Weaknesses:**
- Whitespace-sensitive — indentation errors are silent and hard to debug
- No native schema validation (though JSON Schema can be applied)
- Requires a library (SnakeYAML or Jackson YAML)
- Copy-pasting SQL between YAML and a SQL client can introduce indentation issues

**Java libraries:** SnakeYAML, Jackson (`jackson-dataformat-yaml`)

---

## Alternative 2: Plain SQL Files (one per query)

Directory structure:
```
queries/
  getActiveUsers.sql
  getUserById.sql
  createUser.sql
```

Each file contains just SQL with a metadata header comment:

```sql
-- params: status:string
SELECT * FROM users
WHERE status = {status}
```

**Strengths:**
- SQL files get syntax highlighting and IDE support out of the box
- Can be executed directly in any SQL client for testing
- No format to learn — it's just SQL
- Easy to diff and review in pull requests
- Database tools (DataGrip, DBeaver) can open and validate them directly
- File name is the query name — no duplication

**Weaknesses:**
- Parameter metadata must be encoded in comments (convention-based, not schema-enforced)
- Many small files can clutter a project
- Overloaded queries (same name, different params) need a naming convention (e.g., `getUser_byId.sql`, `getUser_byEmail.sql`)
- No grouping mechanism beyond directory structure

**Java libraries:** None needed beyond `java.nio.file` for file reading. A simple parser for the comment header.

---

## Alternative 3: JSON

```json
{
  "queries": {
    "getActiveUsers": {
      "params": [
        { "name": "status", "type": "string" }
      ],
      "sql": "SELECT * FROM users WHERE status = {status}"
    }
  }
}
```

**Strengths:**
- Universal format, every language has native support
- Strict syntax — no ambiguity (unlike YAML whitespace)
- XMLDB already depends on Gson, so no new dependency needed
- JSON Schema exists for validation

**Weaknesses:**
- No multi-line strings — long SQL must be a single line or use an array of lines joined at runtime
- Requires escaping quotes inside SQL strings
- More verbose than YAML for this use case
- Not pleasant to write by hand for large queries

**Workaround for multi-line:** Use an array of strings:
```json
{
  "sql": [
    "SELECT * FROM users",
    "WHERE status = {status}",
    "ORDER BY created_at DESC"
  ]
}
```

**Java libraries:** Gson (already a dependency)

---

## Alternative 4: TOML

```toml
[queries.getActiveUsers]
params = [
    { name = "status", type = "string" }
]
sql = """
SELECT * FROM users
WHERE status = {status}
"""
```

**Strengths:**
- Multi-line strings are first-class with `"""`
- Cleaner than JSON, stricter than YAML
- Growing adoption in developer tools (Cargo, pyproject.toml)
- No indentation sensitivity

**Weaknesses:**
- Less familiar to Java developers specifically
- Fewer Java libraries available compared to YAML/JSON
- Nested structures can become awkward with TOML's table syntax

**Java libraries:** toml4j, jackson-dataformat-toml

---

## Alternative 5: Java Annotations on Interfaces

```java
public interface UserQueries {

    @Query("SELECT * FROM users WHERE status = {status}")
    @Params({
        @Param(name = "status", type = "string")
    })
    String getActiveUsers = "getActiveUsers";
}
```

Or a more modern approach inspired by Spring Data / MyBatis:

```java
public interface UserRepository {

    @Select("SELECT * FROM users WHERE status = {status}")
    List<User> getActiveUsers(@Param("status") String status);

    @Select("SELECT * FROM users WHERE id = {id}")
    User getUserById(@Param("id") int id);
}
```

**Strengths:**
- Type-safe at compile time — refactoring tools catch renames
- No external files to manage or keep in sync
- IDE autocompletion for parameter names
- Proven pattern (Spring Data JPA, MyBatis, JDBI)
- Parameters are self-documenting from the method signature

**Weaknesses:**
- SQL is embedded in Java source, not externalized (the original goal of XMLDB)
- Changing a query requires recompilation
- Long SQL strings in annotations are hard to read
- Requires an annotation processor or runtime proxy generation

**Java libraries:** MyBatis, JDBI, Spring Data (all implement this pattern fully)

---

## Alternative 6: Kotlin/Groovy DSL (JVM languages)

```kotlin
queries {
    query("getActiveUsers") {
        param("status", type = "string")
        sql("""
            SELECT * FROM users
            WHERE status = {status}
        """)
    }
}
```

**Strengths:**
- Full language power (conditionals, loops, composition)
- Multi-line strings with `"""`
- Type-safe, IDE-supported
- Can compose queries from fragments programmatically

**Weaknesses:**
- Adds a language dependency (Kotlin or Groovy runtime)
- Overkill if queries are static
- Higher learning curve for teams not using these languages
- Blurs the line between configuration and code

---

## Comparison Matrix

| Criteria | XML | YAML | SQL Files | JSON | TOML | Annotations |
|---|---|---|---|---|---|---|
| Readability | Low | High | Highest | Medium | High | High |
| Multi-line SQL | Awkward | Good | Native | Poor | Good | Poor |
| Special char escaping | Required | None | None | Required | None | N/A |
| IDE SQL support | None | None | Full | None | None | Partial |
| Schema validation | XSD | JSON Schema | None | JSON Schema | Limited | Compiler |
| New dependency | None | SnakeYAML | None | None (Gson exists) | toml4j | Annotation processor |
| Familiarity (2026) | Low | High | Highest | High | Medium | High |
| Copy-paste to SQL client | No | No | Yes | No | No | No |
| Java ecosystem support | Strong | Strong | Simple | Strong | Moderate | Strong |

## Recommendation

**For a library like XMLDB, plain SQL files offer the best balance.** They require no new dependencies, give developers native SQL tooling support, and eliminate the format-learning barrier entirely. The only convention needed is a comment header for parameter metadata, which is simple to parse.

**YAML is the strongest alternative if a structured format is preferred.** It solves the main pain points of XML (verbosity, escaping) while keeping queries grouped in a single file. Most Java developers are comfortable with YAML from Spring Boot configuration.

**JSON is practical if minimizing dependencies matters**, since XMLDB already uses Gson. However, the lack of multi-line strings makes it poorly suited for SQL, which is inherently multi-line.

The annotation-based approach is worth considering for a future major version, but it fundamentally changes the library's philosophy from "externalized queries" to "type-safe query interfaces" — which is a different product.

---

## Deep Dive: XMLDB vs Spring Data

Spring Data is the dominant database access framework in the Java ecosystem. This section compares XMLDB's approach against the three Spring Data styles most relevant to it: Spring Data JPA (derived queries), Spring Data JPA (`@Query`), and Spring Data JDBC.

### How Spring Data Works

Spring Data generates repository implementations at runtime from interfaces. The developer declares an interface extending `Repository<T, ID>`, and Spring creates a proxy that handles all JDBC/JPA plumbing.

```java
public interface UserRepository extends JpaRepository<User, Long> {

    // 1. Derived query — Spring generates SQL from the method name
    List<User> findByStatusAndCreatedAfter(String status, LocalDate date);

    // 2. Explicit JPQL via @Query
    @Query("SELECT u FROM User u WHERE u.department = :dept AND u.active = true")
    List<User> findActiveDepartmentMembers(@Param("dept") String department);

    // 3. Native SQL via @Query
    @Query(value = "SELECT * FROM users WHERE status = :status LIMIT :limit",
           nativeQuery = true)
    List<User> findWithLimit(@Param("status") String status, @Param("limit") int limit);
}
```

The equivalent in XMLDB:

```xml
<sqlquery name="findByStatusAndCreatedAfter">
    <param name="status" index="1" type="string"/>
    <param name="date" index="2" type="timestamp"/>
    <query>SELECT * FROM users WHERE status = ? AND created > ?</query>
</sqlquery>

<sqlquery name="findActiveDepartmentMembers">
    <param name="dept" index="1" type="string"/>
    <query>SELECT * FROM users WHERE department = ? AND active = 1</query>
</sqlquery>

<sqlquery name="findWithLimit">
    <param name="status" index="1" type="string"/>
    <param name="limit" index="2" type="int"/>
    <query>SELECT * FROM users WHERE status = ? LIMIT ?</query>
</sqlquery>
```

```java
// Caller code
List<User> users = db.executeQuery("findActiveDepartmentMembers", User.class,
    new Parameter(1, "dept", "Engineering"));
```

### Comparison by Concern

#### 1. Query Definition

| Aspect | XMLDB | Spring Data |
|---|---|---|
| Where queries live | External XML files | Java interfaces (annotations or method names) |
| Query language | Raw SQL | JPQL, HQL, or native SQL |
| Query modification | Edit XML, no recompile | Edit Java source, recompile |
| SQL visibility | Explicit — you see the exact SQL | Hidden for derived queries, explicit for `@Query` |

XMLDB gives full control over the SQL that runs. Spring Data derived queries (`findByStatusAndAge`) generate SQL automatically, which is convenient for simple cases but opaque — developers often don't know what SQL is actually executed until they enable query logging. For complex queries (joins, subqueries, window functions, database-specific syntax), both approaches fall back to writing raw SQL.

#### 2. Parameter Binding

| Aspect | XMLDB | Spring Data |
|---|---|---|
| Binding style | Positional (`?`) or named (`{param}`) | Named (`:param`) or positional (`?1`) |
| Type safety | Runtime — Parameter holds `Object` | Compile-time — method signature types |
| Parameter declaration | XML `<param>` elements + Parameter objects at call site | Method parameters with optional `@Param` |

Spring Data's approach is more ergonomic. The method signature *is* the parameter contract:

```java
// Spring Data — parameter types enforced by compiler
List<User> findByAge(int age);

// XMLDB — wrong type compiles fine, fails at runtime
db.executeQuery("findByAge", User.class,
    new Parameter(1, "age", "not a number"));  // runtime error
```

#### 3. Result Mapping

| Aspect | XMLDB | Spring Data |
|---|---|---|
| Mapping mechanism | ResultSet → JsonObject → Gson → POJO | JPA entity mapping (annotations) or `RowMapper` |
| Configuration | Zero config — Gson maps by column name to field name | Entity annotations (`@Entity`, `@Column`, `@Id`) |
| Relationship handling | None — flat rows only | Full ORM: `@OneToMany`, `@ManyToOne`, lazy loading |
| Custom projections | Not supported | Interface projections, DTO projections, `Tuple` |

XMLDB's Gson-based mapping is simpler to set up — it just matches column names to POJO field names with no annotations. Spring Data JPA requires entity annotations but handles complex object graphs, relationships, and inheritance. For flat result sets from simple queries, XMLDB's approach involves less boilerplate.

#### 4. Connection and Transaction Management

| Aspect | XMLDB | Spring Data |
|---|---|---|
| Connection lifecycle | Manual (`connectDB()` / `closeConnection()`) | Automatic (managed by Spring container) |
| Connection pooling | JNDI lookup or manual | Auto-configured (HikariCP by default) |
| Transactions | Not supported | Declarative (`@Transactional`) |
| Multiple data sources | Manual — one XmlDB instance per database | Configured via Spring — routing, sharding |

This is the largest gap. Spring manages the full connection lifecycle automatically, including pooling, transaction boundaries, and propagation. XMLDB requires the caller to manage connections explicitly, and has no transaction support.

#### 5. Developer Experience

| Aspect | XMLDB | Spring Data |
|---|---|---|
| Boilerplate per query | XML element + Parameter construction at call site | Method declaration on interface |
| IDE support | No navigation from Java to XML query | Click-through from call site to method |
| Refactoring | Rename in XML + all call sites manually | IDE rename refactors everywhere |
| Testing | Requires a database | `@DataJpaTest` with embedded DB, or mock repository |
| Learning curve | Low — just SQL and XML | Moderate — JPA concepts, Spring context, annotations |
| Dependency footprint | 2 JARs (log4j, Gson) | Spring Boot + Spring Data + JPA + Hibernate (~30+ JARs) |

#### 6. Where Each Approach is Stronger

**XMLDB is a better fit when:**
- The project cannot or does not want to adopt Spring
- Full control over exact SQL is required (performance-tuned queries, database-specific syntax)
- The application uses raw JDBC and wants to externalize queries without an ORM
- The deployment environment is constrained (small JAR size, no Spring container)
- The team prefers to treat SQL as a first-class artifact rather than hiding it behind abstractions

**Spring Data is a better fit when:**
- The project already uses Spring Boot
- CRUD operations dominate and custom SQL is rare
- Object relationships (one-to-many, many-to-many) need to be modeled
- Declarative transactions are needed
- The team values compile-time type safety over SQL visibility
- Rapid prototyping matters more than SQL control

### What XMLDB Could Learn from Spring Data

1. **Repository-style interface pattern.** XMLDB could support an interface where each method maps to a named query, providing type safety without adopting Spring:

    ```java
    public interface UserQueries extends XmlDBRepository {
        @NamedQuery("findActiveUsers")
        List<User> findActiveUsers(@Param("status") String status);
    }
    ```

    A dynamic proxy (like `java.lang.reflect.Proxy`) could implement this at runtime, looking up queries from the XML by method name or annotation value.

2. **Method-name-based parameter binding.** Instead of requiring `@Param` or manual `Parameter` construction, the library could inspect method parameter names (available since Java 8 with `-parameters` compiler flag) to match `{param}` placeholders automatically.

3. **Pagination and sorting support.** Spring Data's `Pageable` and `Sort` parameters are appended to queries automatically. XMLDB could support this by appending `LIMIT`/`OFFSET` and `ORDER BY` clauses to the base SQL from the XML file.

4. **Optional and Stream return types.** Spring Data repositories can return `Optional<T>` (for single results) and `Stream<T>` (for lazy iteration). This is more expressive than XMLDB's current approach of returning `null` for no results or a `List<T>` that must be fully materialized.

### What Spring Data Could Learn from XMLDB

1. **SQL as an externalized artifact.** Spring Data's `@Query` annotations embed SQL in Java source, making it hard to review SQL changes in isolation. XMLDB's externalized approach means SQL can be reviewed, diffed, and even versioned independently.

2. **Zero-annotation result mapping.** XMLDB maps columns to POJO fields by name without any annotations on the target class. Spring Data JPA requires `@Entity`, `@Table`, `@Column`, `@Id` — even for simple read-only projections. Spring Data JDBC is closer to XMLDB's approach here but still requires an `@Id` annotation.

3. **Minimal dependency footprint.** XMLDB works with 2 JARs. A Spring Data JPA setup pulls in Spring Core, Spring Context, Spring AOP, Spring TX, Spring Data Commons, Spring Data JPA, Hibernate Core, Jakarta Persistence, and their transitive dependencies. For applications that just need "named SQL queries with parameter binding," this is significant overhead.

---

## Improving Caller-Side Readability

The code that calls XMLDB to execute queries is where developers spend most of their time. This section examines the current caller experience and proposes concrete improvements, including the impact of dropping `?` parameters entirely in favor of `{namedParam}`.

### The Problem Today

A typical XMLDB call site looks like this:

```java
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class,
    new Parameter(1, "customerId", customerId),
    new Parameter(2, "status", "shipped"),
    new Parameter(3, "minTotal", 50.0));
```

With the corresponding XML:

```xml
<sqlquery name="getOrdersByCustomerAndStatus">
    <param name="customerId" index="1" type="int"/>
    <param name="status" index="2" type="string"/>
    <param name="minTotal" index="3" type="double"/>
    <query>SELECT * FROM orders WHERE customer_id = ? AND status = ? AND total > ?</query>
</sqlquery>
```

There are several readability problems here:

1. **Index tracking is manual and error-prone.** The caller must know that `customerId` is parameter 1, `status` is 2, and `minTotal` is 3. If parameters are reordered in the XML, every call site must be updated. If a new parameter is inserted in the middle, all subsequent indices shift.

2. **The index is redundant information.** The caller already passes the name (`"customerId"`), and the XML already declares the name. The index exists only to map to `?` positions — it's plumbing that's visible to the application developer.

3. **Three arguments per parameter.** Every parameter requires specifying index, name, and value. Two of these (index and name) are metadata that the framework should resolve, not the caller.

4. **No connection between `?` and the parameter.** Reading the SQL `WHERE customer_id = ? AND status = ?`, a developer cannot tell which `?` corresponds to which parameter without counting positions and cross-referencing the `<param>` declarations.

5. **The XML `<param>` declarations duplicate information.** Both the XML and the call site declare the same parameter name. If they drift apart, the query silently fails to match at runtime.

### Improvement 1: Drop `?`, Use Only `{namedParam}`

If the SQL in XML files uses `{namedParam}` exclusively:

```xml
<sqlquery name="getOrdersByCustomerAndStatus">
    <param name="customerId" type="int"/>
    <param name="status" type="string"/>
    <param name="minTotal" type="double"/>
    <query>
        SELECT * FROM orders
        WHERE customer_id = {customerId}
        AND status = {status}
        AND total > {minTotal}
    </query>
</sqlquery>
```

The SQL is now self-documenting. A developer reading the query can see exactly which parameter goes where without consulting the `<param>` declarations.

#### Impact on the Call Site

With named parameters, the `index` field on `Parameter` is no longer needed. This enables a simplified factory method:

```java
// Before (positional)
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class,
    new Parameter(1, "customerId", customerId),
    new Parameter(2, "status", "shipped"),
    new Parameter(3, "minTotal", 50.0));

// After (named only)
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class,
    Parameter.of("customerId", customerId),
    Parameter.of("status", "shipped"),
    Parameter.of("minTotal", 50.0));
```

The index is gone. The parameter order at the call site no longer matters — `{customerId}` will be bound to the `customerId` parameter regardless of where it appears in the varargs list.

#### Impact on the XML `<param>` Declarations

With `{namedParam}` in the SQL, the `<param>` elements become partially redundant. The parameter names are already embedded in the SQL itself. The `<param>` elements still serve two purposes:

1. **Type declarations** — though in practice the runtime type of the value passed by the caller determines the JDBC setter used, not the XML type.
2. **Query signature matching** — `QueryTree.getQuery()` matches by name + parameter count/names to support overloaded queries.

This opens a further simplification: the `<param>` elements could be dropped entirely if the library parses `{paramName}` placeholders from the SQL to derive the parameter signature automatically:

```xml
<!-- Fully simplified: no <param> elements needed -->
<sqlquery name="getOrdersByCustomerAndStatus">
    <query>
        SELECT * FROM orders
        WHERE customer_id = {customerId}
        AND status = {status}
        AND total > {minTotal}
    </query>
</sqlquery>
```

The query's parameter signature (three parameters: `customerId`, `status`, `minTotal`) is inferred from the SQL. Type is determined at runtime from the Java objects passed in.

#### Impact on Query Matching

Currently, `QueryTree.getQuery()` supports multiple queries with the same name but different parameter signatures. With `?` parameters, this matching relies on `<param>` declarations. With `{namedParam}`, the matching could compare the set of placeholder names extracted from the SQL against the names of the `Parameter` objects passed by the caller. The behavior is preserved — it just derives the signature from the SQL rather than from `<param>` elements.

#### What Breaks

Dropping `?` support is a **breaking change** for existing XML query files. Any project using XMLDB would need to update all `<query>` elements from:

```
SELECT * FROM users WHERE id = ? AND status = ?
```

to:

```
SELECT * FROM users WHERE id = {userId} AND status = {status}
```

This is a mechanical transformation, but it touches every query. For a library used across multiple projects, this warrants a major version bump.

There are no cases where `?` can express something that `{namedParam}` cannot. The `?` notation is a JDBC concept — it exists because `PreparedStatement` uses positional binding. The `{namedParam}` notation is a library-level abstraction that maps to `?` internally. The only loss is familiarity for developers accustomed to JDBC's native `?` syntax.

### Improvement 2: Fluent Parameter Builder

For queries with many parameters, even the simplified `Parameter.of()` can become verbose. A builder pattern reduces noise:

```java
// Builder approach
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class,
    Params.with("customerId", customerId)
          .and("status", "shipped")
          .and("minTotal", 50.0));
```

The `Params` class would produce a `List<Parameter>` or `Map<String, Object>` internally:

```java
public class Params {
    private final Map<String, Object> map = new LinkedHashMap<>();

    public static Params with(String name, Object value) {
        Params p = new Params();
        p.map.put(name, value);
        return p;
    }

    public Params and(String name, Object value) {
        map.put(name, value);
        return this;
    }

    public Map<String, Object> toMap() { return map; }
}
```

### Improvement 3: Map Overloads for Named Queries

The generic query methods already accept `Map<String, Object>`. The named query methods could too, making both APIs consistent:

```java
// Current generic query API
Map<String, Object> params = new HashMap<>();
params.put("status", "shipped");
db.executeGenericQuery("SELECT * FROM orders WHERE status = {status}", params);

// Proposed: same Map style for named queries
Map<String, Object> params = Map.of(
    "customerId", customerId,
    "status", "shipped",
    "minTotal", 50.0);
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class, params);
```

With Java 9+ `Map.of()`, this is concise and needs no `Parameter` class at all. For Java 8, a static helper like `Params.with()` above fills the gap.

### Improvement 4: Inline Parameter Syntax

For the simplest cases — queries with one or two parameters — even `Parameter.of()` adds ceremony. A varargs approach using alternating name/value pairs is the most compact:

```java
// Alternating name, value pairs
User user = db.queryForOne("getUserById", User.class,
    "userId", 42);

List<Order> orders = db.query("getOrdersByCustomerAndStatus", Order.class,
    "customerId", customerId,
    "status", "shipped",
    "minTotal", 50.0);
```

This trades compile-time safety (odd-length arrays, type mismatches) for brevity. It works best when combined with runtime validation that throws clear errors for malformed argument lists.

### Summary: Effect of Dropping `?` Support

| Aspect | Effect |
|---|---|
| SQL readability | Improved — parameters are named inline, no counting `?` positions |
| Call site readability | Improved — `index` argument eliminated from `Parameter` |
| XML `<param>` elements | Can be made optional — parameter names derived from SQL |
| Query matching | Unchanged — can match by name set instead of index set |
| Parameter ordering | No longer matters — bound by name, not position |
| Backward compatibility | Broken — all existing XML files must be updated |
| JDBC familiarity | Lost — `?` is native JDBC syntax, `{param}` is library-specific |
| Same parameter used twice | Supported — `WHERE a = {id} OR b = {id}` binds `id` to both positions |
| Migration effort | Mechanical — find-and-replace `?` with `{name}` per query |

The strongest argument for dropping `?` is that it enables every other readability improvement in this section. As long as `?` is supported, the `index` field must exist, `Parameter` must carry it, and the call site must specify it. Named parameters make the index obsolete, which cascades into a simpler `Parameter` class, optional `<param>` declarations, and a more fluent caller API.

The strongest argument against is backward compatibility. A pragmatic path is to support both during a transition period (as the library does today) but document `{namedParam}` as the preferred style and deprecate `?` in a future major version.

---

## `Parameter.of()` vs `Map.of()` as the Caller API

Two competing approaches exist for passing named parameters at the call site:

```java
// Option A: Parameter.of() with alternating name/value varargs
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class, Parameter.of(
    "customerId", customerId,
    "status", "shipped",
    "minTotal", 50.0));

// Option B: Map.of() (Java 9+)
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class, Map.of(
    "customerId", customerId,
    "status", "shipped",
    "minTotal", 50.0));
```

Both read almost identically at the call site. The differences are beneath the surface.

### How `Parameter.of()` Would Work

`Parameter.of()` would be a static factory method that accepts alternating `String` name / `Object` value pairs and returns something the `executeQuery` methods can consume — either a `Map<String, Object>`, a `Parameter[]`, or a dedicated wrapper type:

```java
public class Parameter {

    // Returns a Map for use with the Map-based overloads
    public static Map<String, Object> of(Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                "Parameter.of() requires an even number of arguments (name/value pairs)");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (!(namesAndValues[i] instanceof String)) {
                throw new IllegalArgumentException(
                    "Parameter name at position " + i + " must be a String, got: "
                    + namesAndValues[i].getClass().getName());
            }
            map.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return map;
    }
}
```

### Comparison

| Aspect | `Parameter.of(...)` | `Map.of(...)` |
|---|---|---|
| **Readability at call site** | Identical | Identical |
| **Java version** | Works on Java 8+ | `Map.of()` requires Java 9+ |
| **Type safety** | None — all args are `Object`, name/value swap compiles fine | Partial — keys are `String`, values are `Object` |
| **Odd argument count** | Runtime error only | Compile error (overloads are type-checked) |
| **Domain signal** | Says "these are query parameters" — lives in XMLDB's namespace | Says "this is a generic map" — no library association |
| **IDE discoverability** | Developers type `Parameter.` and see the factory | Developers must know to use `Map.of()` |
| **Null values** | Can be supported | `Map.of()` throws `NullPointerException` on null values |
| **Duplicate keys** | Can choose behavior (last-wins or throw) | `Map.of()` throws `IllegalArgumentException` |
| **Max parameters** | Unlimited (varargs) | `Map.of()` supports up to 10 key/value pairs; beyond that requires `Map.ofEntries()` |
| **New code needed** | ~15 lines in Parameter class | None — standard library |
| **Return type** | Returns `Map<String, Object>` (or custom type) | Already is `Map<String, Object>` |

### The Type Safety Problem

The core weakness of `Parameter.of()` is that the alternating varargs pattern has no compile-time type checking. All arguments are `Object`, so the compiler cannot distinguish names from values:

```java
// Compiles fine — but name and value are swapped
Parameter.of(customerId, "customerId", "shipped", "status")

// Compiles fine — odd number of args, fails at runtime
Parameter.of("customerId", customerId, "status")
```

`Map.of()` is slightly better because its overloads enforce `String` keys at compile time:

```java
// Map.of() overloads are typed: of(K, V, K, V, ...)
Map.of("customerId", customerId, "status", "shipped")  // OK
Map.of(customerId, "customerId")  // Compile error if customerId is not String
```

However, `Map.of()` still accepts `Object` values, so value-type errors are not caught at compile time in either approach.

### The Java 8 Problem

The project targets Java 8, where `Map.of()` does not exist. On Java 8, the equivalent is:

```java
// Java 8 — verbose
Map<String, Object> params = new HashMap<>();
params.put("customerId", customerId);
params.put("status", "shipped");
params.put("minTotal", 50.0);
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class, params);
```

This is significantly worse than either option. `Parameter.of()` solves the Java 8 problem by providing the concise alternating-pairs syntax without requiring `Map.of()`:

```java
// Java 8 — with Parameter.of()
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class, Parameter.of(
    "customerId", customerId,
    "status", "shipped",
    "minTotal", 50.0));
```

This is the strongest argument for `Parameter.of()`: **it provides the `Map.of()` ergonomics on Java 8**.

### The Null Value Problem

`Map.of()` does not allow null values — it throws `NullPointerException`. This is a real concern for SQL parameters where `NULL` is a valid value:

```java
// This throws NullPointerException
Map.of("deletedAt", null)

// Parameter.of() can handle this
Parameter.of("deletedAt", null)  // works — binds SQL NULL
```

A `Parameter.of()` implementation can call `statement.setNull()` when it encounters a null value, which is the correct JDBC behavior.

### Recommendation

**Implement `Parameter.of()` that returns `Map<String, Object>`.** This gives the library:

- Java 8 compatibility with concise syntax
- Null value support
- A domain-specific entry point that's discoverable via `Parameter.`
- No new overloads needed on `XmlDB` — the existing `Map<String, Object>` overloads accept the return value directly

The implementation is small (~15 lines), and the call site reads naturally:

```java
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class, Parameter.of(
    "customerId", customerId,
    "status", "shipped",
    "minTotal", 50.0));
```

For projects on Java 9+, callers can choose either `Parameter.of()` or `Map.of()` — both return the same type and work with the same method overloads. The two are interchangeable, and the library doesn't force a choice.

---

## What Changes If the Target JVM Is Raised

The library currently targets Java 8 (source/target 8 in the Maven compiler plugin). Java 8 was released in 2014 and reached end of public updates in 2019. Raising the target to a modern LTS version (Java 17 or Java 21) — or even to the current Java 25 — unlocks language features and API improvements that directly address many of the readability and design issues discussed above.

This section walks through the relevant features by version and their impact on XMLDB.

### Java 9 (2017): `Map.of()` and Module System

**`Map.of()` and `Map.ofEntries()`** become available, which eliminates the primary argument for `Parameter.of()`:

```java
// Java 9+ — concise, no custom factory needed
List<Order> orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class, Map.of(
    "customerId", customerId,
    "status", "shipped",
    "minTotal", 50.0));
```

`Map.of()` provides typed overloads for up to 10 key-value pairs. For queries with more than 10 parameters (rare), `Map.ofEntries(Map.entry("k", v), ...)` handles the overflow.

The null-value limitation of `Map.of()` remains. If the library needs to support null parameter values (for SQL `NULL`), `Parameter.of()` is still needed as a null-safe alternative, or callers use `HashMap` for those cases.

**Module system (JPMS):** The library could provide a `module-info.java` to declare its exports cleanly. This is optional but signals that the library is a well-structured modern dependency:

```java
module no.skaperiet.xmldb {
    requires java.sql;
    requires java.naming;
    requires log4j;
    requires com.google.gson;
    exports no.skaperiet.xmldb;
    exports no.skaperiet.xmldb.query;
}
```

### Java 10 (2018): `var`

Local variable type inference reduces boilerplate at the call site:

```java
// Java 8
Map<String, Object> params = new HashMap<>();
params.put("status", "active");
List<User> users = db.executeQuery("getActiveUsers", User.class, params);

// Java 10+
var params = Map.of("status", "active");
var users = db.executeQuery("getActiveUsers", User.class, params);
```

This is a caller-side convenience — no library changes needed, but it makes the Map-based API look cleaner in documentation and examples.

### Java 14 (2020): Records and Switch Expressions

**Records** could replace the `Parameter` class entirely. A record is an immutable data carrier with auto-generated `equals()`, `hashCode()`, `toString()`, and accessor methods:

```java
// Current Parameter class: 79 lines with constructors, getters, toString, compare
// Record replacement:
public record Parameter(String name, Object value) {

    public static Map<String, Object> of(Object... namesAndValues) {
        // same factory as before
    }
}
```

The `index` and `type` fields are dropped (unnecessary with `{namedParam}` and runtime type detection). The class shrinks from 79 lines to under 10.

Records could also be used as query result types. Today, callers must create full POJO classes with fields and getters for Gson mapping. With records:

```java
// Java 14+ — result POJO as a record
public record Order(long id, String status, double total, String createdAt) {}

List<Order> orders = db.executeQuery("getOrders", Order.class, Map.of("status", "shipped"));
```

Gson 2.10+ supports records natively. Earlier versions need a `TypeAdapterFactory` or the library could switch to a record-aware deserializer.

**Switch expressions** clean up the type-dispatch code in `setParameters` and `convertResultSetToJson`. The current 40-line `if/else if` chain in `convertResultSetToJson` becomes:

```java
// Java 14+ switch expression
switch (rsmd.getColumnType(i)) {
    case Types.BIGINT    -> obj.addProperty(columnName, rs.getLong(columnName));
    case Types.INTEGER   -> obj.addProperty(columnName, rs.getInt(columnName));
    case Types.BOOLEAN   -> obj.addProperty(columnName, rs.getBoolean(columnName));
    case Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR
                         -> obj.addProperty(columnName, rs.getString(columnName));
    case Types.TIMESTAMP -> {
        Timestamp ts = rs.getTimestamp(columnName);
        if (ts != null) obj.addProperty(columnName, ts.toString());
    }
    // ... etc
    default              -> obj.addProperty(columnName, rs.getString(i));
};
```

This is more compact and eliminates the risk of missing a `break` (not applicable to `if/else`, but the switch form groups related types naturally).

### Java 16 (2021): Pattern Matching for `instanceof`

The `setParameters` method currently uses a chain of `instanceof` checks with casts:

```java
// Current (Java 8)
if (param instanceof Integer) {
    statement.setInt(paramIndex, (Integer) param);
} else if (param instanceof Long) {
    statement.setLong(paramIndex, (Long) param);
}
```

With pattern matching:

```java
// Java 16+
if (param instanceof Integer v) {
    statement.setInt(paramIndex, v);
} else if (param instanceof Long v) {
    statement.setLong(paramIndex, v);
}
```

The cast is eliminated. Small improvement per line, but it accumulates across `setParameters`, `prepareNamedStatement`, and `convertResultSetToJson` — all of which have these chains.

### Java 17 (2021, LTS): Sealed Classes

Sealed classes could model the query result in a type-safe way if XMLDB were to support richer return types:

```java
public sealed interface QueryResult<T> {
    record Success<T>(List<T> rows) implements QueryResult<T> {}
    record Empty<T>() implements QueryResult<T> {}
    record Error<T>(String queryName, SQLException cause) implements QueryResult<T> {}
}
```

This replaces the current inconsistent error handling (some methods return `null`, some return empty lists, some throw) with a single type that callers can pattern-match on.

**Java 17 as a target is the most practical modernization step.** It is the current widely-adopted LTS. Raising the target from Java 8 to Java 17 unlocks `Map.of()`, `var`, records, switch expressions, pattern matching for `instanceof`, sealed classes, and text blocks — all in one move. Most production Java applications have migrated to at least Java 17 by 2026.

### Java 15 / 17: Text Blocks

Text blocks allow multi-line strings with `"""`. This doesn't affect XML query files directly, but it dramatically improves the annotation-based or inline-SQL style if the library ever supports it:

```java
// Java 15+ text blocks
db.executeGenericQuery("""
    SELECT o.id, o.status, c.name
    FROM orders o
    JOIN customers c ON o.customer_id = c.id
    WHERE o.status = {status}
      AND o.total > {minTotal}
    ORDER BY o.created_at DESC
    """, Map.of("status", "shipped", "minTotal", 50.0));
```

Compare to the Java 8 equivalent:

```java
db.executeGenericQuery(
    "SELECT o.id, o.status, c.name " +
    "FROM orders o " +
    "JOIN customers c ON o.customer_id = c.id " +
    "WHERE o.status = {status} " +
    "  AND o.total > {minTotal} " +
    "ORDER BY o.created_at DESC",
    Parameter.of("status", "shipped", "minTotal", 50.0));
```

Text blocks make `executeGenericQuery` a genuinely pleasant API for ad-hoc SQL, competitive with the externalized XML approach for one-off queries.

### Java 21 (2023, LTS): Virtual Threads and Sequenced Collections

**Virtual threads (Project Loom)** change the cost model of database connections. Currently, each XMLDB instance holds one JDBC connection, and blocking on `statement.executeQuery()` ties up an OS thread. With virtual threads, blocking is cheap — thousands of concurrent queries can run on a small thread pool.

This doesn't require library API changes, but it changes the design advice. On Java 21+, the single-connection-per-instance model is less of a bottleneck because the calling code can cheaply spawn virtual threads that each create their own short-lived `XmlDB` instance:

```java
// Java 21+ — cheap concurrent queries
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var ordersTask  = scope.fork(() -> db.executeQuery("getOrders", Order.class, Map.of("status", "shipped")));
    var metricsTask = scope.fork(() -> db.executeQuery("getMetrics", Metric.class, Map.of()));
    scope.join();
    // both queries ran concurrently on virtual threads
}
```

**Sequenced collections:** `LinkedHashMap` implements `SequencedMap`, which gives ordered maps a first-class API. This matters for `Parameter.of()` — a `LinkedHashMap` return type guarantees parameter iteration order matches insertion order, which is useful for logging and debugging.

### Java 21+: Pattern Matching for Switch

Combines `instanceof` pattern matching with switch expressions, enabling a single expression for the entire type-dispatch in `setParameters`:

```java
// Java 21+
private void bindValue(PreparedStatement stmt, int index, Object value) throws SQLException {
    switch (value) {
        case Integer v  -> stmt.setInt(index, v);
        case Long v     -> stmt.setLong(index, v);
        case Double v   -> stmt.setDouble(index, v);
        case String v   -> stmt.setString(index, v);
        case Timestamp v -> stmt.setTimestamp(index, v);
        case null       -> stmt.setNull(index, Types.NULL);
        default         -> stmt.setObject(index, value);
    }
}
```

This replaces both `setParameters` and the binding loop in `prepareNamedStatement` with a single, exhaustive switch. The `case null` branch solves the null-parameter problem at the language level.

### Java 25 (2025): Flexible Constructor Bodies, Compact Source Files

Java 25 continues incremental improvements:

- **Flexible constructor bodies** allow statements before `super()` / `this()`, which simplifies `QueryTree`'s constructor that currently must call `this(elemName, openDocument(uri).getDocumentElement())` with no way to validate `uri` first.
- **Compact source files** (JEP 477, implicitly declared classes) allow single-file utility programs without class declarations — useful for test scripts or query-file validators, though not for the library itself.

These are minor improvements for XMLDB specifically.

### Summary: Feature Unlocks by Target Version

| Minimum Target | Key Unlocks for XMLDB |
|---|---|
| **Java 9** | `Map.of()` eliminates need for `Parameter.of()`; module system |
| **Java 10** | `var` reduces call-site verbosity |
| **Java 14** | Records for `Parameter` and result POJOs; switch expressions for type dispatch |
| **Java 15** | Text blocks for inline SQL in `executeGenericQuery` |
| **Java 16** | Pattern matching for `instanceof` in binding code |
| **Java 17 (LTS)** | Sealed classes for result types; all of the above stable. **Best migration target.** |
| **Java 21 (LTS)** | Virtual threads; pattern matching for switch; sequenced collections |
| **Java 25** | Flexible constructors; incremental improvements |

### Recommendation: Target Java 17

Java 17 is the sweet spot for XMLDB modernization:

1. **It is the most widely deployed LTS in 2026.** Most organizations that have moved past Java 8 landed on Java 17 or 21.
2. **It unlocks every feature that matters for the API redesign:** `Map.of()`, records, text blocks, switch expressions, pattern matching, sealed classes.
3. **It does not require Java 21+ features** (virtual threads, structured concurrency) that would change the library's threading model — XMLDB is a synchronous library and that's fine.
4. **The `Parameter.of()` factory becomes optional**, since `Map.of()` is available. It can still be provided for null-value support, but it's no longer necessary for basic ergonomics.

On Java 17, the "ideal" call site becomes:

```java
var orders = db.executeQuery("getOrdersByCustomerAndStatus", Order.class, Map.of(
    "customerId", customerId,
    "status", "shipped",
    "minTotal", 50.0));
```

And the XML query file:

```xml
<sqlquery name="getOrdersByCustomerAndStatus">
    <query>
        SELECT * FROM orders
        WHERE customer_id = {customerId}
        AND status = {status}
        AND total > {minTotal}
    </query>
</sqlquery>
```

No `Parameter` class, no index, no `<param>` elements, no `new`. Just a query name, a result type, and a map of values.

---

## Beyond XML: If Queries Are Just Name + SQL, Do You Need a Data Format at All?

Once `<param>` elements are dropped and the SQL uses `{namedParam}` placeholders, the XML reduces to:

```xml
<sqlquery name="getOrdersByCustomer">
    <query>
        SELECT * FROM orders
        WHERE customer_id = {customerId}
        AND status = {status}
        ORDER BY created_at DESC
    </query>
</sqlquery>
```

The only information here is a **name** and a **SQL string**. XML, YAML, and JSON are all general-purpose data serialization formats designed for nested, typed, structured data. Using them to store a flat list of name/string pairs is like using a spreadsheet to write a shopping list — it works, but the tool is far heavier than the task.

The question becomes: what is the simplest possible container for a collection of named SQL strings that keeps SQL separate from Java code?

### Approach 1: Plain SQL Files, One Per Query

The most minimal approach. The file name is the query name. The file content is pure SQL:

```
queries/
  getOrdersByCustomer.sql
  getActiveUsers.sql
  createOrder.sql
  updateOrderStatus.sql
```

`queries/getOrdersByCustomer.sql`:
```sql
SELECT * FROM orders
WHERE customer_id = {customerId}
AND status = {status}
ORDER BY created_at DESC
```

That's it. No format, no metadata, no escaping, no parsing library. The file is the query.

**Loading in Java:**

```java
// Library reads all .sql files from a directory
// File name (minus .sql) becomes the query name
XmlDB db = new XmlDB(dataSource, "queries/");
```

**Strengths:**
- **Zero format overhead.** There is no format to learn, no syntax to get wrong, no escaping rules. The file contains exactly what will be sent to the database.
- **Full IDE support.** Every IDE and editor provides SQL syntax highlighting, autocompletion, and error checking for `.sql` files. DataGrip, DBeaver, and other database tools can open and execute them directly.
- **Copy-paste workflow.** A developer can write and test a query in a SQL client, then save it as a `.sql` file. No translation step.
- **Clean diffs.** A pull request that changes a query shows exactly the SQL change, with no surrounding format noise.
- **No parsing dependency.** The library reads files with `Files.readString()` (Java 11+) or `new String(Files.readAllBytes(...))` (Java 8). No XML parser, no YAML parser, no JSON parser.

**Weaknesses:**
- **One file per query.** A project with 200 queries has 200 files. This can be managed with subdirectories (`queries/orders/`, `queries/users/`), but it's still more filesystem clutter than a single file.
- **Overloaded queries.** If two queries share a name but differ by parameters (e.g., `getUser` by ID vs. by email), they need distinct file names — the file system doesn't support overloading. In practice this is fine: `getUserById.sql` and `getUserByEmail.sql` are clearer names than two overloaded `getUser` entries anyway.
- **No grouping metadata.** You can't express "these five queries belong to the orders module" except through directory structure.

**Precedent:** This is exactly the approach used by [Yesql](https://github.com/krisajenern/yesql) (Clojure), [PugSQL](https://pugsql.org/) (Python), and [aiosql](https://github.com/nackjicholson/aiosql) (Python). All three are well-regarded libraries that rejected XML/YAML/JSON in favor of plain SQL files.

### Approach 2: SQL Files with Comment-Based Naming (Multi-Query)

Multiple queries in a single `.sql` file, separated by a naming comment:

`queries/orders.sql`:
```sql
-- :name getOrdersByCustomer
SELECT * FROM orders
WHERE customer_id = {customerId}
AND status = {status}
ORDER BY created_at DESC

-- :name createOrder
INSERT INTO orders (customer_id, product_id, quantity, status)
VALUES ({customerId}, {productId}, {quantity}, 'pending')

-- :name updateOrderStatus
UPDATE orders
SET status = {status}, updated_at = NOW()
WHERE id = {orderId}
```

The convention: `-- :name queryName` marks the start of a query. Everything until the next `-- :name` (or end of file) is the SQL body. The `--` prefix makes it a SQL comment, so the file remains valid SQL.

**Loading in Java:**

```java
// Library splits the file on "-- :name" markers
XmlDB db = new XmlDB(dataSource, "queries/orders.sql");
// Or a directory of such files:
XmlDB db = new XmlDB(dataSource, "queries/");
```

**Strengths:**
- **Still just SQL.** The file is valid SQL. The naming comments are invisible to SQL tools that don't understand them.
- **Grouped queries.** Related queries live in one file (`orders.sql`, `users.sql`), matching how developers think about their domain.
- **Fewer files.** 200 queries might live in 10-15 files instead of 200.
- **IDE support preserved.** `.sql` files still get syntax highlighting. The comment markers don't interfere.
- **Simple parser.** Splitting on `-- :name` is a few lines of code — no library needed.

**Weaknesses:**
- **Convention-based.** A typo in `-- :name` silently breaks query loading. There's no schema to validate against.
- **Can't execute individual queries directly.** A SQL client running the file would execute all queries sequentially. The developer must manually select the query they want to test.
- **Comment syntax is file-format metadata disguised as SQL.** It's a lightweight convention, but it is still a convention that developers must learn.

**Precedent:** This is the [HugSQL](https://www.hugsql.org/) pattern (Clojure) and [aiosql](https://github.com/nackjicholson/aiosql) (Python). HugSQL additionally supports `-- :doc` for documentation and `-- :result` for result-type hints, all in comment syntax.

### Approach 3: SQL with Frontmatter

Borrowed from static site generators (Jekyll, Hugo), a query file has a metadata header separated from the body by `---`:

`queries/getOrdersByCustomer.sql`:
```
---
name: getOrdersByCustomer
description: Fetches orders for a customer filtered by status
---
SELECT * FROM orders
WHERE customer_id = {customerId}
AND status = {status}
ORDER BY created_at DESC
```

**Strengths:**
- Clean separation of metadata and SQL
- Extensible — can add description, author, tags, or result-type hints without changing the SQL
- The SQL portion below `---` is uncontaminated — no comments, no markers

**Weaknesses:**
- The frontmatter block breaks SQL syntax highlighting in most editors (they see the `---` and metadata as invalid SQL)
- Requires a slightly more complex parser than the comment-based approach
- The metadata section is yet another mini-format to learn (YAML-in-frontmatter)
- Unnecessary if the only metadata is the name (which is already the filename)

This approach makes sense only if queries need richer metadata. For name + SQL, it adds complexity for no benefit over Approach 1.

### Approach 4: Java Resource Bundles (`.properties`)

Java has a built-in key-value format: properties files. Multi-line values use `\` line continuation:

`queries.properties`:
```properties
getOrdersByCustomer = \
    SELECT * FROM orders \
    WHERE customer_id = {customerId} \
    AND status = {status} \
    ORDER BY created_at DESC

getActiveUsers = \
    SELECT * FROM users \
    WHERE status = {status}
```

**Strengths:**
- Built into Java — `Properties.load()` is in the standard library
- Single file, name-value pairs — exactly the data model we need
- Familiar to Java developers from `application.properties`, `messages.properties`

**Weaknesses:**
- **Line continuations are ugly and error-prone.** Every line except the last must end with `\`. Forgetting one silently truncates the SQL.
- **No SQL highlighting.** Editors see `.properties` files, not SQL. No autocompletion, no syntax checking.
- **Escaping.** `=` and `:` in SQL must be escaped (`\=`, `\:`), which occasionally bites on SQL like `CASE WHEN x := y`.
- **No comments per query.** Properties files support `#` comments, but they can't be associated with a specific query.

Properties files are a poor fit for multi-line content. The format was designed for short string values like `error.notfound=User not found`, not for 20-line SQL statements.

### Approach 5: Custom Catalog Format

A minimal custom format designed specifically for named SQL queries:

`queries.sql`:
```
=== getOrdersByCustomer

SELECT * FROM orders
WHERE customer_id = {customerId}
AND status = {status}
ORDER BY created_at DESC

=== createOrder

INSERT INTO orders (customer_id, product_id, quantity, status)
VALUES ({customerId}, {productId}, {quantity}, 'pending')

=== getActiveUsers

SELECT * FROM users WHERE status = {status}
```

The convention: `=== queryName` on its own line starts a new query. Everything between markers is the SQL body, trimmed of leading/trailing whitespace.

**Strengths:**
- **Visually scannable.** The `===` markers are prominent — easy to spot when scrolling through a large file.
- **Trivial to parse.** Split on lines starting with `===`, take the rest as name and body.
- **No escaping at all.** SQL is completely raw between markers.
- **One file, grouped queries**, like the comment-based approach but without the SQL-comment disguise.

**Weaknesses:**
- **Non-standard.** Every other format in this list has tooling, specifications, and precedent. This is a bespoke format that only XMLDB understands.
- **No syntax highlighting.** Editors won't recognize it as SQL (though some can be configured to).
- **The `===` marker is arbitrary.** Other libraries use different conventions; there's no ecosystem alignment.

### Comparison

| Aspect | One file per query | Comment markers | Frontmatter | Properties | Custom catalog | XML (current) |
|---|---|---|---|---|---|---|
| Format overhead | None | Minimal | Moderate | Moderate | Minimal | High |
| SQL highlighting | Full | Full | Partial | None | None | None |
| Copy-paste to SQL client | Direct | Manual select | Need to strip header | No | No | No |
| Multi-line SQL | Native | Native | Native | Awkward (`\`) | Native | Requires escaping |
| Query grouping | Directories | Files | Directories | Single file | Single file | Single file |
| File count | One per query | One per module | One per query | One | One per module | One per module |
| Parse complexity | Read file | Split on comment | Split on `---` | `Properties.load()` | Split on marker | XML parser |
| Dependency needed | None | None | None | None | None | javax.xml |
| Existing ecosystem | Yesql, PugSQL, aiosql | HugSQL, aiosql | Static site generators | Java built-in | None | MyBatis |
| Editor/IDE support | Best | Good | Poor | Poor | Poor | Moderate |

### Recommendation

**Approach 2 (comment-based naming in `.sql` files) offers the best balance for XMLDB.** It preserves the current behavior of grouping related queries in a single file while being pure SQL that works in any editor and SQL tool. The parser is trivial to implement, and the pattern is proven by HugSQL and aiosql.

The loading API would remain the same — a file path or directory:

```java
// Load all queries from a single file
XmlDB db = new XmlDB(dataSource, "queries/orders.sql");

// Load all .sql files from a directory (existing behavior)
XmlDB db = new XmlDB(dataSource, "queries/");
```

The library can support both `.xml` and `.sql` files simultaneously during a migration period — detect the file extension and use the appropriate parser.

**Approach 1 (one file per query) is even simpler** and should be supported alongside Approach 2. For projects with many small queries, one-file-per-query is the cleanest model. For projects that prefer grouping, comment-based `.sql` files provide that. The library can handle both: `.sql` files containing `-- :name` markers are parsed as multi-query catalogs; `.sql` files without markers are treated as single queries named after the file.

The end state: a developer can store queries as:

```
queries/
  orders.sql          ← multiple queries, separated by -- :name
  getUserById.sql     ← single query, name derived from filename
  reports/
    monthlySales.sql  ← subdirectory for grouping
```

No XML, no YAML, no JSON, no annotations. Just SQL.
