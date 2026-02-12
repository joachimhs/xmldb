package no.skaperiet.sqlstore;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sun.misc.Unsafe;

/**
 * Maps JDBC ResultSet rows directly to POJOs or records via reflection.
 * No external dependencies — replaces the previous Gson-based two-hop conversion.
 *
 * <p>Supports regular classes (via no-arg constructor + field assignment)
 * and Java 17 records (via canonical constructor).</p>
 */
final class RowMapper {

    private static final Unsafe UNSAFE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private RowMapper() {}

    /**
     * Maps all rows in the ResultSet to instances of the given class.
     * Field/component resolution happens once, before the row loop.
     */
    static <T> List<T> mapRows(ResultSet rs, Class<T> clazz) throws SQLException {
        if (clazz.isRecord()) {
            return mapRowsToRecord(rs, clazz);
        }
        return mapRowsToClass(rs, clazz);
    }

    private static <T> List<T> mapRowsToClass(ResultSet rs, Class<T> clazz) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        // Resolve column-to-field mapping once
        Field[] fields = new Field[columnCount + 1]; // 1-indexed
        for (int i = 1; i <= columnCount; i++) {
            String colName = columnLabel(meta, i);
            try {
                Field f = clazz.getDeclaredField(colName);
                f.setAccessible(true);
                fields[i] = f;
            } catch (NoSuchFieldException e) {
                fields[i] = null;
            }
        }

        // Try no-arg constructor first; fall back to Unsafe allocation
        Constructor<T> ctor = null;
        try {
            ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
        } catch (NoSuchMethodException ignored) {}

        List<T> results = new ArrayList<>();
        while (rs.next()) {
            try {
                @SuppressWarnings("unchecked")
                T obj = (ctor != null) ? ctor.newInstance()
                        : (T) UNSAFE.allocateInstance(clazz);
                for (int i = 1; i <= columnCount; i++) {
                    if (fields[i] != null) {
                        fields[i].set(obj, getTypedValue(rs, i, fields[i].getType()));
                    }
                }
                results.add(obj);
            } catch (ReflectiveOperationException e) {
                throw new SQLException("Failed to map row to " + clazz.getName(), e);
            }
        }
        return results;
    }

    private static <T> List<T> mapRowsToRecord(ResultSet rs, Class<T> recordClass)
            throws SQLException {
        RecordComponent[] components = recordClass.getRecordComponents();
        Class<?>[] paramTypes = Arrays.stream(components)
                .map(RecordComponent::getType)
                .toArray(Class[]::new);

        // Build column-name → column-index lookup
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            colIndex.put(columnLabel(meta, i), i);
        }

        List<T> results = new ArrayList<>();
        while (rs.next()) {
            try {
                Object[] args = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    Integer idx = colIndex.get(components[i].getName());
                    args[i] = (idx != null) ? getTypedValue(rs, idx, paramTypes[i]) : null;
                }
                results.add(recordClass.getDeclaredConstructor(paramTypes).newInstance(args));
            } catch (ReflectiveOperationException e) {
                throw new SQLException(
                        "Failed to map row to record " + recordClass.getName(), e);
            }
        }
        return results;
    }

    /**
     * Returns the effective column name (label preferred over name).
     */
    private static String columnLabel(ResultSetMetaData meta, int i) throws SQLException {
        String label = meta.getColumnLabel(i);
        return (label != null && !label.isEmpty()) ? label : meta.getColumnName(i);
    }

    /**
     * Reads a value from the ResultSet and converts it to the target field type.
     * Handles primitives, boxed types, String, BigDecimal, java.sql date/time,
     * and java.time types directly — no intermediate JSON representation.
     */
    private static Object getTypedValue(ResultSet rs, int col, Class<?> type)
            throws SQLException {
        if (rs.getObject(col) == null) {
            if (type.isPrimitive()) return primitiveDefault(type);
            return null;
        }

        if (type == int.class    || type == Integer.class)   return rs.getInt(col);
        if (type == long.class   || type == Long.class)      return rs.getLong(col);
        if (type == double.class || type == Double.class)     return rs.getDouble(col);
        if (type == float.class  || type == Float.class)      return rs.getFloat(col);
        if (type == boolean.class|| type == Boolean.class)    return rs.getBoolean(col);
        if (type == short.class  || type == Short.class)      return rs.getShort(col);
        if (type == byte.class   || type == Byte.class)       return rs.getByte(col);
        if (type == String.class)                             return rs.getString(col);
        if (type == java.math.BigDecimal.class)               return rs.getBigDecimal(col);
        if (type == java.sql.Timestamp.class)                 return rs.getTimestamp(col);
        if (type == java.sql.Date.class)                      return rs.getDate(col);
        if (type == java.sql.Time.class)                      return rs.getTime(col);
        if (type == java.time.LocalDate.class) {
            var d = rs.getDate(col);
            return d != null ? d.toLocalDate() : null;
        }
        if (type == java.time.LocalDateTime.class) {
            var t = rs.getTimestamp(col);
            return t != null ? t.toLocalDateTime() : null;
        }
        if (type == java.time.LocalTime.class) {
            var t = rs.getTime(col);
            return t != null ? t.toLocalTime() : null;
        }

        // Fallback
        return rs.getObject(col);
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == int.class)     return 0;
        if (type == long.class)    return 0L;
        if (type == double.class)  return 0.0d;
        if (type == float.class)   return 0.0f;
        if (type == boolean.class) return false;
        if (type == short.class)   return (short) 0;
        if (type == byte.class)    return (byte) 0;
        if (type == char.class)    return '\0';
        return null;
    }
}
