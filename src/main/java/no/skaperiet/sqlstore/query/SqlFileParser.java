package no.skaperiet.sqlstore.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses .sql files with -- :name convention into SqlQuery objects.
 *
 * Format:
 *   -- :name queryName
 *   SELECT * FROM table WHERE col = {param}
 *
 *   -- :name anotherQuery
 *   INSERT INTO table (a, b) VALUES ({a}, {b})
 */
public final class SqlFileParser {

    private static final String NAME_PREFIX = "-- :name ";

    private SqlFileParser() {}

    /**
     * Parses the content of a .sql file into a list of SqlQuery objects.
     * Each query is delimited by a -- :name declaration line.
     */
    public static List<SqlQuery> parse(String content) {
        List<SqlQuery> queries = new ArrayList<>();
        String[] lines = content.split("\n");
        String currentName = null;
        StringBuilder currentSql = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(NAME_PREFIX)) {
                if (currentName != null) {
                    String sql = currentSql.toString().trim();
                    if (!sql.isEmpty()) {
                        queries.add(new SqlQuery(currentName, sql));
                    }
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

        if (currentName != null) {
            String sql = currentSql.toString().trim();
            if (!sql.isEmpty()) {
                queries.add(new SqlQuery(currentName, sql));
            }
        }

        return queries;
    }
}
