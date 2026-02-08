# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

This is a Maven project targeting Java 8.

- **Build:** `mvn compile`
- **Package:** `mvn package`
- **Install to local repo:** `mvn install`
- **Deploy:** `mvn deploy` (publishes to Nexus at `135.181.145.18:8081`)

There are no tests in this project.

## Architecture

XMLDB is a Java library that maps named SQL queries defined in XML files to JDBC `PreparedStatement` execution. It acts as a lightweight query-by-name layer over JDBC.

### Core Flow

1. **XML Query Files** define `<sqlquery>` elements, each containing a `name`, `<param>` declarations (with `name`, `index`, `type`), and a `<query>` element with the SQL.
2. **`QueryTree`** parses one or more XML files into a `HashSet<Query>`. It supports loading from a single file or a directory of `.xml` files.
3. **`Query`** represents a single named query with its parameter definitions. Query lookup matches by name AND parameter list (name + index must match).
4. **`XmlDB`** is the main entry point. It takes DB credentials and an XML query file/directory path, builds a `QueryTree`, and provides `executeQuery`/`executeUpdate` methods that look up queries by name and bind `Parameter` objects to `PreparedStatement` placeholders.

### Key Classes

- **`XmlDB`** — Connection management (direct JDBC or JNDI DataSource pool), query execution, ResultSet-to-JSON conversion via Gson. Queries returning objects use `GsonBuilder.setDateFormat("yyyy-MM-dd HH:mm:ss")` to deserialize rows into POJOs.
- **`QueryTree`** — XML parser and query registry. Queries are matched by name + parameter signature (not just name).
- **`Query`** — Holds query name, SQL string, and parameter definitions parsed from XML.
- **`Parameter`** — Represents a query parameter with `index` (1-based, for PreparedStatement binding), `name`, `value`, and `type`.

### Connection Configuration

The JDBC driver class must be set via system property: `-Dno.skaperiet.xmldb.driverClassFile=com.mysql.jdbc.Driver`

Non-pooled connections use the hardcoded `jdbc:mysql://` URL scheme.

### Dependencies

- **log4j 1.2** for logging
- **Gson 2.8.8** for ResultSet-to-JSON/POJO conversion
