# Security Audit — XMLDB Library

Audit date: 2026-02-08
Scope: All source files under `src/main/java/no/skaperiet/xmldb/`

---

## Critical

### 1. SQL Injection via `executeGenericQuery` and `executeGenericUpdate`

**Files:** `XmlDB.java:326`, `XmlDB.java:365`

Both methods accept a raw SQL string and pass it directly to `conn.prepareStatement(sql)` with no parameter binding. Despite using `PreparedStatement`, the SQL is fully formed before being passed in, so there is no parameterization benefit — it behaves identically to `Statement.execute(sql)`.

Any caller passing user-controlled input into these methods is vulnerable to SQL injection.

```java
public JsonObject executeGenericQuery(String sql) throws SQLException {
    statement = conn.prepareStatement(sql);  // sql is caller-supplied, no binding
    resultSet = statement.executeQuery();
```

**Recommendation:** Remove these methods or require callers to pass parameters separately and bind them via `PreparedStatement.setXxx()`.

---

### 2. SQL Injection via `getSqlForQuery` / `setParametersInSQL`

**Files:** `XmlDB.java:235`, `XmlDB.java:554`

`setParametersInSQL` builds a SQL string by splitting on `?` and concatenating parameter values inline. String values are wrapped in single quotes but **never escaped**:

```java
newSQL += "'" + ((String) param).toString() + "'";
```

A string parameter value containing `'` (e.g., `O'Brien` or `'; DROP TABLE users; --`) will break out of the quote and allow arbitrary SQL injection.

**Recommendation:** This method should not exist in production code. If generating displayable SQL for logging/debugging, clearly mark it as unsafe and never execute the output. If it must exist, use proper escaping or a dedicated SQL formatter.

---

### 3. XML External Entity (XXE) Injection

**Files:** `QueryTree.java:63`, `QueryTree.java:77`

Both `openDocument` overloads create a `DocumentBuilder` from a default `DocumentBuilderFactory` without disabling external entity resolution:

```java
DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
return builder.parse(uri);
```

If an attacker can influence the contents of a query XML file, they can read arbitrary files from the server, perform SSRF, or cause denial of service via entity expansion ("billion laughs" attack).

**Recommendation:** Disable external entities and DTDs:

```java
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
```

---

## High

### 4. Credentials Stored in Memory as Plaintext Strings

**File:** `XmlDB.java:37-39`

Database username and password are stored as `String` fields for the lifetime of the `XmlDB` instance:

```java
private String user;
private String pass;
```

Java `String` objects are immutable and remain in the heap until garbage collected, making them visible in heap dumps, core dumps, and debugging tools.

**Recommendation:** Accept credentials through a `DataSource` or `Supplier<Connection>` rather than storing raw credential strings. If direct credentials are unavoidable, use `char[]` and clear after use.

---

### 5. Credentials Exposed in JDBC Connection URL

**File:** `XmlDB.java:148`

The password is embedded in the JDBC URL:

```java
conn = DriverManager.getConnection("jdbc:mysql://" + host + "/" + db
    + "?user=" + user + "&password=" + pass + "&autoreconnect=true");
```

This URL string may appear in logs, stack traces, monitoring tools, or JMX beans. Additionally, neither `user`, `host`, nor `db` are validated or sanitized — a malicious value for `host` containing `?` or `&` characters could manipulate the JDBC URL.

**Recommendation:** Use the `DriverManager.getConnection(url, user, password)` overload which keeps credentials out of the URL string. Validate that `host` and `db` contain only expected characters.

---

### 6. Arbitrary Class Instantiation via System Property

**File:** `XmlDB.java:143-145`

The JDBC driver is loaded by reading a class name from a system property and instantiating it via reflection:

```java
String driver = System.getProperty("no.skaperiet.xmldb.driverClassFile");
drv = (Driver) Class.forName(driver).newInstance();
```

If an attacker can set system properties (e.g., via JNDI injection, shared hosting, or misconfigured application servers), they can instantiate arbitrary classes. The class only needs to be castable to `Driver`, but the constructor side effects execute regardless of the cast.

**Recommendation:** Validate the driver class name against an allowlist, or use `DriverManager` auto-discovery (JDBC 4.0+) which eliminates the need for `Class.forName` entirely.

---

### 7. No TLS Enforcement on Database Connections

**File:** `XmlDB.java:148`

The MySQL connection string does not enforce SSL/TLS:

```
jdbc:mysql://host/db?user=...&password=...&autoreconnect=true
```

Credentials and all query data travel over the network in plaintext, vulnerable to eavesdropping and man-in-the-middle attacks.

**Recommendation:** Add `useSSL=true&requireSSL=true&verifyServerCertificate=true` to the connection string, or configure TLS at the DataSource level.

---

## Medium

### 8. Uncontrolled JSON Deserialization from Database Content

**File:** `XmlDB.java:480-488`

`handleAsStringOrArray` parses database column values that look like JSON arrays directly into `JsonArray` objects:

```java
if (stringValue != null && stringValue.startsWith("[") && stringValue.endsWith("]")) {
    JsonArray jsonArray = new Gson().fromJson(stringValue, JsonArray.class);
    obj.add(column_name, jsonArray);
}
```

If an attacker can control database content (e.g., via a separate injection point), they can inject arbitrary JSON structures that downstream consumers may not expect, potentially leading to logic bugs or secondary injection.

**Recommendation:** Validate or schema-check parsed JSON before adding it to the response object, or document this behavior clearly so consumers are aware.

---

### 9. Exception Swallowing Hides Security Events

**Files:** `XmlDB.java:261-263`, `XmlDB.java:277-279`, `XmlDB.java:317-319`, `XmlDB.java:494-496`

Multiple empty catch blocks silently discard exceptions:

```java
try { rs.close(); } catch (Exception e) { }
```

Failed close operations, unexpected SQLExceptions, and connection errors are invisible. This can hide active attacks (e.g., connection hijacking, statement tampering) and makes incident investigation impossible.

**Recommendation:** At minimum, log caught exceptions at debug/warn level. Never swallow exceptions from security-relevant operations like connection and statement management.

---

### 10. No Path Validation on XML Query File Input

**File:** `XmlDB.java:46-82`

The constructor accepts a file path string (`dbQueryFile`) and passes it directly to `Files.isDirectory`, `File.listFiles`, and the XML parser without any validation:

```java
public XmlDB(String dbName, String dbHost, String dbUser, String dbPass, String dbQueryFile) {
    ...
    if (Files.isDirectory(Paths.get(dbQueryFile))) {
        File[] files = f.listFiles(filter);
```

If this path is derived from user input, an attacker can use path traversal (e.g., `../../etc/`) to load arbitrary XML files, which combined with the XXE vulnerability (finding #3) allows reading arbitrary system files.

**Recommendation:** Validate that the resolved path is within an expected base directory using `Path.normalize()` and `Path.startsWith()`.

---

### 11. Resource Leaks Enable Denial of Service

**Files:** `XmlDB.java:244-284`, `XmlDB.java:490-526`

- `PreparedStatement` objects are created but only closed in some code paths (the `finally` block at line 277 is commented out).
- `ResultSet` fields (`rs`) are instance-level and overwritten without guaranteed closure.
- In `executeGenericUpdate` (line 379), `resultSet.close()` is called in the `finally` block but `resultSet` is never assigned (always `null`), causing a `NullPointerException` that is silently swallowed.

Leaked statements and connections can exhaust database connection pools and file descriptors, enabling denial of service.

**Recommendation:** Use try-with-resources for all `PreparedStatement` and `ResultSet` objects. Do not store `ResultSet` as an instance field.

---

## Low

### 12. `log.error` Used for Non-Error Connection Event

**File:** `XmlDB.java:142`

```java
log.error("Creating DB Connection");
```

This logs a normal connection creation at ERROR level. In production, this creates noise that can cause operators to miss real security-relevant error events.

---

## Summary

| # | Severity | Issue |
|---|----------|-------|
| 1 | Critical | SQL injection via `executeGenericQuery`/`executeGenericUpdate` |
| 2 | Critical | SQL injection via `setParametersInSQL` (no quote escaping) |
| 3 | Critical | XXE injection in XML parser |
| 4 | High | Plaintext credentials in memory |
| 5 | High | Credentials exposed in JDBC URL string |
| 6 | High | Arbitrary class instantiation via system property |
| 7 | High | No TLS enforcement on DB connections |
| 8 | Medium | Uncontrolled JSON deserialization from DB content |
| 9 | Medium | Exception swallowing hides security events |
| 10 | Medium | No path validation on XML file input |
| 11 | Medium | Resource leaks enable denial of service |
| 12 | Low | Incorrect log level for normal events |
