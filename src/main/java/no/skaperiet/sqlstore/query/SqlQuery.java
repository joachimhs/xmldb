package no.skaperiet.sqlstore.query;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable representation of a named SQL query with {paramName} placeholders.
 * Parameter names are extracted automatically from the SQL text.
 */
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
