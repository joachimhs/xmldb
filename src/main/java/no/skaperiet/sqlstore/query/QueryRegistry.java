package no.skaperiet.sqlstore.query;

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

/**
 * A registry of named SQL queries loaded from .sql files.
 * Supports loading from a single file or a directory of .sql files.
 * Queries are looked up by name and parameter signature.
 */
public final class QueryRegistry {

    private static final Logger log = Logger.getLogger(QueryRegistry.class.getName());
    private final Map<String, List<SqlQuery>> queriesByName = new HashMap<>();

    /**
     * Loads queries from a single .sql file or a directory of .sql files.
     *
     * @param path a .sql file or a directory containing .sql files
     * @return a populated QueryRegistry
     * @throws IOException if the path cannot be read
     */
    public static QueryRegistry load(Path path) throws IOException {
        var registry = new QueryRegistry();
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.list(path)) {
                List<Path> sqlFiles = files
                        .filter(f -> f.toString().endsWith(".sql"))
                        .sorted()
                        .toList();
                for (Path f : sqlFiles) {
                    registry.loadFile(f);
                }
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
     *
     * @param name the query name
     * @param paramNames the set of parameter names provided by the caller
     * @return the matching SqlQuery, or null if no match found
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
