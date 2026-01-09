package org.example.db.dialect;

import org.example.db.datasource.DatabaseType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 方言注册表：根据 {@link DatabaseType} 找到对应的 {@link DatabaseDialect}。
 */
@Component
public class DatabaseDialectRegistry {

    private final Map<DatabaseType, DatabaseDialect> dialects;

    public DatabaseDialectRegistry(List<DatabaseDialect> dialectList) {
        Objects.requireNonNull(dialectList, "dialectList must not be null");
        Map<DatabaseType, DatabaseDialect> map = new EnumMap<>(DatabaseType.class);
        for (DatabaseDialect dialect : dialectList) {
            map.put(dialect.getType(), dialect);
        }
        this.dialects = Map.copyOf(map);
    }

    public DatabaseDialect getDialect(DatabaseType type) {
        DatabaseDialect dialect = dialects.get(type);
        if (dialect == null) {
            throw new IllegalArgumentException("不支持的数据库类型: " + type + "（已注册方言=" + dialects.keySet() + "）");
        }
        return dialect;
    }
}

