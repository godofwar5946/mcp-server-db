package org.example.db.util;

import java.sql.ResultSet;
import java.sql.SQLException;

public final class JdbcValues {
    private JdbcValues() {}
    public static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
    public static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
