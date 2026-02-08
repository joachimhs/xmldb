# REQUIREMENTS.md

Requirements specification for the XMLDB library. This document is intended to be sufficient input for an LLM agent to recreate the library from scratch.

## Overview

XMLDB is a Java library that externalizes SQL queries into XML files and executes them against a JDBC database by name. It provides a query-by-name abstraction over JDBC where SQL statements are defined in XML, looked up at runtime by name and parameter signature, and executed safely via PreparedStatement.

## Project Setup

- Java 8 source/target compatibility
- Maven build, packaged as a JAR
- Group ID: `no.skaperiet.xmldb`, Artifact ID: `xmldb`
- Package root: `no.skaperiet.xmldb`
- Dependencies: log4j 1.2.x for logging, Gson 2.8.x for JSON conversion
- No other runtime dependencies; uses only JDK standard library (JDBC, JNDI, javax.xml)

## XML Query File Format

Queries are defined in XML files. The root element can be anything. Inside it, each query is a `<sqlquery>` element with the following structure:

```xml
<sqlquery name="getUser">
    <param name="userId" index="1" type="int"/>
    <param name="status" index="2" type="string"/>
    <query>SELECT * FROM users WHERE id = ? AND status = ?</query>
</sqlquery>
```

Queries can also use named parameters in the XML file: 

```xml
<sqlquery name="getUser">
    <param name="userId" type="int"/>
    <param name="status" type="string"/>
    <query>SELECT * FROM users WHERE id = {id} AND status = {status}</query>
</sqlquery>
```

- The `<sqlquery>` element has a `name` attribute identifying the query
- Zero or more `<param>` child elements, each with:
  - `name` (string): the parameter name used for matching
  - `index` (integer): the 1-based positional index for PreparedStatement binding
  - `type` (string, optional): the parameter type (defaults to "string")
- A single `<query>` child element containing the SQL text with `?` placeholders
- Multiple `<sqlquery>` elements with the same name are allowed, distinguished by their parameter signatures (different parameter count or different name/index pairs)

## Classes and Their Requirements

### Parameter (`no.skaperiet.xmldb.query.Parameter`)

Represents a query parameter with:
- `name` (String): parameter name
- `index` (int): 1-based positional index for PreparedStatement binding
- `value` (Object): the runtime value to bind (null when parsed from XML definition)
- `type` (String): defaults to `"string"`

Constructors:
- `Parameter(String name, int index)` — used when parsing XML definitions
- `Parameter(int index, String name)` — delegates to above (reversed argument order convenience)
- `Parameter(int index, String name, Object value)` — used at execution time
- `Parameter(int index, String name, Object value, String type)` — full constructor

Methods:
- Getters for all fields: `getName()`, `getIndex()`, `getValue()`, `getType()`
- `setType(String type)` — setter for type
- `compare(Parameter other)` — returns true if both `index` and `name` are equal (used for matching caller-supplied parameters against XML-defined parameters)
- `toString()` — format: `"name - value(index, type)"`

### Query (`no.skaperiet.xmldb.query.Query`)

Represents a single named SQL query parsed from an XML element.

Construction:
- Takes a DOM `Element` representing a `<sqlquery>` element
- Reads the `name` attribute
- Reads all `<param>` child elements into a list of Parameter objects (name, index, and optionally type from XML attributes)
- Reads the first `<query>` child element's text content as the SQL string
- If no `<query>` element exists, throws `NoSuchElementException`

Methods:
- `getName()` — returns the query name
- `getQuery()` — returns the SQL string
- `getParametersAsString()` — returns the parameter list's `toString()` representation
- `compare(List params)` — determines if a list of Parameter objects matches this query's parameter signature:
  - Returns false if sizes differ
  - Returns true if both are empty
  - For each parameter in the input list, checks if there exists a matching parameter in the query's definition (matching by name and index via `Parameter.compare()`)
  - All input parameters must find a match; returns false on first unmatched parameter

### QueryTree (`no.skaperiet.xmldb.query.QueryTree`)

A registry of Query objects parsed from XML files. Stores queries in a `HashSet`.

Construction:
- `QueryTree(String elemName, String uri)` — parses XML from a file path, extracts all elements with tag name `elemName`, creates Query objects from each
- `QueryTree(String elemName, Element root)` — same but from an existing DOM Element

XML Parsing:
- Uses `DocumentBuilderFactory` / `DocumentBuilder` to parse XML
- Has two static `openDocument` methods: one accepting a `String` URI (file path), one accepting an `InputStream`
- The XML parser must be hardened against XXE attacks: disable DTD declarations, external general entities, external parameter entities, external DTD loading, XInclude, and entity reference expansion

Methods:
- `appendQueriesFromFile(String elemName, String uri)` — parses another XML file and adds its queries to the existing set (used when loading from a directory of XML files)
- `getQuery(String name, List params)` — iterates all stored queries, finds one where the name matches AND `query.compare(params)` returns true. Returns null if no match found. This allows multiple queries with the same name but different parameter signatures (method overloading by parameter list)

### XmlDB (`no.skaperiet.xmldb.XmlDB`)

The main entry point. Manages database connections and executes named queries.

#### Construction

`XmlDB(String dbName, String dbHost, String dbUser, String dbPass, String dbQueryFile)`

- Stores connection credentials
- Loads the QueryTree from `dbQueryFile`:
  - If `dbQueryFile` is a **directory**: scans for all `.xml` files in the directory, creates a QueryTree from the first file, then appends queries from each subsequent file
  - If `dbQueryFile` is a **regular file**: creates a QueryTree from that single file
  - The element tag name used to find queries is `"sqlquery"`
- Calls `connectDB()` to establish the initial connection

#### Connection Management

Two modes:

1. **Direct JDBC** (default, `pooled = false`):
   - Reads the driver class name from system property `no.skaperiet.xmldb.driverClassFile`
   - Instantiates the driver via `Class.forName(driver).newInstance()`
   - Connects via `DriverManager.getConnection` with URL: `jdbc:mysql://<host>/<db>?user=<user>&password=<pass>&autoreconnect=true`

2. **JNDI Connection Pool** (`pooled = true`):
   - Looks up a `DataSource` via JNDI using the `dbName` as the lookup name
   - Gets connections from the DataSource
   - Alternatively, a DataSource can be injected via `setDataSource(DataSource)`

- `connectDB()` — establishes or re-establishes the connection. Called automatically on construction, and re-called before each query if the connection is null or closed
- `closeConnection()` — closes the current connection

#### Named Query Execution (XML-defined queries)

All named query methods look up a query from the QueryTree by name and parameter signature, then execute it via PreparedStatement.

**Parameter binding** supports these Java types, mapped to the appropriate `PreparedStatement.setXxx()` call:
- `Integer` -> `setInt`
- `Long` -> `setLong`
- `Timestamp` -> `setTimestamp`
- `Double` -> `setDouble`
- `String` -> `setString`
- Any other type -> `setObject`

The Parameter's `index` field determines which `?` placeholder position is bound (1-based).

Methods:

- `executeQuery(String name, Parameter... parameters)` — executes a SELECT query by name. Stores the ResultSet as instance state. Returns `true` if the result set has at least one row. The caller then iterates using `next()`, `getString()`, `getInt()`, `getDate()`, `getDouble()`, `getLong()`, `getResultSet()`, `numRows()`, `resetPosition()`.

- `executeUpdate(String name, Parameter... parameters)` — executes an INSERT/UPDATE/DELETE by name. Returns `true` if at least one row was affected, `false` if zero rows affected, `null` if no matching query was found.

- `executeQuery(String name, Class<T> clazz, Parameter... parameters)` — executes a SELECT and automatically maps each result row to a POJO of type `T`. Uses Gson for deserialization: each row is first converted to a `JsonObject`, then deserialized to `T` using `GsonBuilder.setDateFormat("yyyy-MM-dd HH:mm:ss").create().fromJson(jsonObject, clazz)`. Returns a `List<T>`.

- `executeQueryForId(String name, Class<T> clazz, Parameter... parameters)` — like the above but expects exactly zero or one result. Returns the single object, or null if no results. Throws `SQLException` if more than one row is returned.

#### Generic Query Execution (ad-hoc SQL with named parameters)

For ad-hoc SQL not defined in XML files. The SQL uses `{paramName}` placeholders which are replaced with `?` and bound safely via PreparedStatement.

- `executeGenericQuery(String sql, Map<String, Object> namedParams)` — executes a SELECT with named parameters. Returns a `JsonObject` with:
  - `"rows"`: a `JsonArray` of `JsonObject` elements (one per row)
  - `"columns"`: a `JsonArray` of column name strings (derived from the first row's ResultSetMetaData)
  - Columns use the column label if it differs from the column name (for aliased columns)

- `executeGenericUpdate(String sql, Map<String, Object> namedParams)` — executes an INSERT/UPDATE/DELETE with named parameters. Returns the number of rows affected.

Named parameter parsing:
- Scans the SQL for `{word}` patterns using regex `\{(\w+)\}`
- Replaces each with `?`
- Collects the corresponding values from the map in the order they appear in the SQL
- Binds them positionally to the PreparedStatement using the same type-dispatch as named query execution
- Throws `SQLException` if a placeholder name is not found in the map

#### ResultSet to JSON Conversion

When converting a ResultSet row to a `JsonObject`, each column is mapped based on its JDBC type:

| JDBC Type | JSON Property Type | Method |
|---|---|---|
| BIGINT | Number (long) | `rs.getLong()` |
| REAL | Number (float) | `rs.getFloat()` |
| BOOLEAN | Boolean | `rs.getBoolean()` |
| DOUBLE | Number (double) | `rs.getDouble()` |
| FLOAT | Number (double) | `rs.getDouble()` |
| INTEGER | Number (int) | `rs.getInt()` |
| NVARCHAR | String | `rs.getNString()` |
| VARCHAR | String | `rs.getString()` |
| CHAR | String | `rs.getString()` |
| NCHAR | String | `rs.getNString()` |
| LONGNVARCHAR | String | `rs.getNString()` |
| LONGVARCHAR | String | `rs.getString()` |
| TINYINT | Number (byte) | `rs.getByte()` |
| SMALLINT | Number (short) | `rs.getShort()` |
| DATE | String (toString) | `rs.getDate().toString()` (null-safe) |
| TIME | String (toString) | `rs.getTime().toString()` |
| TIMESTAMP | String (toString) | `rs.getTimestamp().toString()` (null-safe) |
| BIT | Boolean | `rs.getBoolean()` |
| NUMERIC | Number (BigDecimal) | `rs.getBigDecimal()` |
| DECIMAL | Number (BigDecimal) | `rs.getBigDecimal()` |
| All others | String | `rs.getString()` |

Column naming: if a column has a label (alias) that differs from the column name, the label is used as the JSON property key.

#### ResultSet Cursor Methods

For the cursor-style API (`executeQuery(name, params)` without a class):

- `next()` — advances the cursor, returns false if no result set or end of results
- `getString(String columnName)`, `getInt(String columnName)`, `getDate(String columnName)`, `getDouble(String columnName)` — delegate directly to the current ResultSet
- `getLong(int columnIndex)` — by column index
- `getResultSet()` — returns the raw ResultSet
- `numRows()` — navigates to last row, gets row number, resets to before-first. Returns total row count
- `resetPosition()` — resets cursor to before-first

### XmlDbPopulatedObject (`no.skaperiet.xmldb.XmlDbPopulatedObject`)

An interface with a single method:

```java
public void populate(ResultSet resultset) throws SQLException;
```

Intended for objects that can populate themselves from a ResultSet row.

## Security Requirements

- XML parsing must disable external entities (XXE protection)
- All SQL execution must use PreparedStatement with proper parameter binding
- The generic query API must use `{paramName}` placeholders that are converted to `?` bind variables — never concatenate user values into SQL strings
- Named parameter parsing must throw an error if a placeholder references a parameter name not present in the input map

## Logging

- Uses log4j (Logger) throughout
- Debug level: query text, parameter values, query tree loading progress
- Error level: connection failures, missing query matches
- Info level: driver creation, XML parsing lifecycle events
