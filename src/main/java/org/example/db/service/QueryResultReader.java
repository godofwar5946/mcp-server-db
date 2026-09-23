package org.example.db.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.db.config.DbExplorerProperties;
import org.example.db.service.dto.QueryColumn;
import org.springframework.stereotype.Component;
import java.io.*;
import java.sql.*;
import java.util.*;

@Component
public class QueryResultReader {
    private final DbExplorerProperties properties;
    private final ObjectMapper mapper;
    public QueryResultReader(DbExplorerProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }
    public record Result(List<QueryColumn> columns, List<Map<String, Object>> rows, boolean limited, List<String> warnings) {}

    public Result read(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        if (count > 1024) throw new SQLException("结果列数超过 1024");
        List<QueryColumn> columns = new ArrayList<>();
        Set<String> reserved = new HashSet<>();
        Set<String> used = new HashSet<>();
        for (int i = 1; i <= count; i++) reserved.add(label(meta, i));
        for (int i = 1; i <= count; i++) {
            String label = label(meta, i);
            String key = label;
            for (int suffix = 2; used.contains(key); suffix++) {
                key = label + "_" + suffix;
                while (reserved.contains(key)) key = label + "_" + (++suffix);
            }
            used.add(key);
            columns.add(new QueryColumn(key, label, meta.getColumnType(i), meta.getColumnTypeName(i)));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean limited = false;
        long bytes = jsonSize(columns) + 128;
        if (bytes > properties.getResultMaxBytes()) throw new SQLException("列定义超过结果字节数上限");
        boolean[] fieldTruncated = { false };
        while (rs.next()) {
            if (rows.size() >= maxRows) { limited = true; break; }
            Map<String, Object> row = new LinkedHashMap<>();
            long rowBytes = 2;
            boolean oversized = false;
            for (int i = 1; i <= count; i++) {
                QueryColumn column = columns.get(i - 1);
                Object cell = value(rs, i, column.jdbcType(), fieldTruncated);
                rowBytes += jsonSize(column.key()) + jsonSize(cell) + 2;
                if (bytes + rowBytes > properties.getResultMaxBytes()) {
                    oversized = true;
                    break;
                }
                row.put(column.key(), cell);
            }
            long size = jsonSize(row) + 1L;
            if (oversized || bytes + size > properties.getResultMaxBytes()) {
                limited = true;
                warnings.add("结果达到字节数上限；请减少列数、缩小查询范围或分页");
                break;
            }
            rows.add(row);
            bytes += size;
        }
        if (fieldTruncated[0]) {
            limited = true;
            warnings.add("部分大字段已截断；长文本请用 SUBSTRING/SUBSTR 按偏移分段查询，每段不超过 "
                    + properties.getFieldMaxLength() + " 字符；二进制字段以 Base64 返回");
        }
        return new Result(List.copyOf(columns), rows, limited, warnings);
    }

    private String label(ResultSetMetaData meta, int i) throws SQLException {
        String label = meta.getColumnLabel(i);
        return label == null || label.isBlank() ? "column_" + i : label;
    }

    private long jsonSize(Object value) throws SQLException {
        try { return mapper.writeValueAsBytes(value).length; }
        catch (IOException e) { throw new SQLException("结果无法序列化", e); }
    }

    private Object value(ResultSet rs, int index, int type, boolean[] truncated) throws SQLException {
        try {
            return switch (type) {
                case Types.NULL -> null;
                case Types.BOOLEAN, Types.BIT -> {
                    boolean value = rs.getBoolean(index);
                    yield rs.wasNull() ? null : value;
                }
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                    long value = rs.getLong(index);
                    yield rs.wasNull() ? null : value;
                }
                case Types.NUMERIC, Types.DECIMAL -> rs.getBigDecimal(index);
                case Types.FLOAT, Types.REAL, Types.DOUBLE -> {
                    double value = rs.getDouble(index);
                    yield rs.wasNull() ? null : Double.isFinite(value) ? value : Double.toString(value);
                }
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                    try (InputStream input = rs.getBinaryStream(index)) {
                        if (input == null) yield null;
                        byte[] bytes = input.readNBytes(properties.getFieldMaxLength() + 1);
                        if (bytes.length > properties.getFieldMaxLength()) {
                            bytes = Arrays.copyOf(bytes, properties.getFieldMaxLength());
                            truncated[0] = true;
                        }
                        yield Base64.getEncoder().encodeToString(bytes);
                    }
                }
                case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                        Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB, Types.OTHER, Types.ARRAY, Types.SQLXML -> {
                    try (Reader reader = rs.getCharacterStream(index)) { yield readText(reader, truncated); }
                }
                default -> {
                    // Detach dates, UUID/ROWID and vendor types before closing the connection.
                    String text = rs.getString(index);
                    if (text != null && text.length() > properties.getFieldMaxLength()) {
                        truncated[0] = true;
                        text = text.substring(0, properties.getFieldMaxLength());
                    }
                    yield text;
                }
            };
        } catch (IOException e) { throw new SQLException("读取结果字段失败", e); }
    }

    private String readText(Reader reader, boolean[] truncated) throws IOException {
        if (reader == null) return null;
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[2048];
        int limit = properties.getFieldMaxLength();
        while (result.length() <= limit) {
            int n = reader.read(buffer, 0, Math.min(buffer.length, limit + 1 - result.length()));
            if (n < 0) break;
            if (n == 0) continue;
            result.append(buffer, 0, n);
        }
        if (result.length() > limit) {
            truncated[0] = true;
            result.setLength(limit);
            if (!result.isEmpty() && Character.isHighSurrogate(result.charAt(result.length() - 1))) result.setLength(result.length() - 1);
        }
        return result.toString();
    }
}
