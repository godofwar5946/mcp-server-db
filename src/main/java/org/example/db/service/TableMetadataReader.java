package org.example.db.service;

import org.example.db.datasource.*;
import org.example.db.model.TableSchema;
import org.example.db.model.TableSchema.*;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.*;

@Component
public class TableMetadataReader {
    private final DatabaseClientRegistry clients;
    public TableMetadataReader(DatabaseClientRegistry clients) { this.clients = clients; }

    public TableSchema enrich(DatabaseClient client, TableSchema schema) {
        List<KeyInfo> primary = new ArrayList<>();
        List<ForeignKeyInfo> foreign = new ArrayList<>();
        List<IndexInfo> indexes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (Connection connection = client.dataSource().getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            boolean mysql = clients.resolveDatabaseType(client.id()) == DatabaseType.MYSQL;
            String catalog = mysql ? schema.table().schema() : connection.getCatalog();
            String namespace = mysql ? null : schema.table().schema();
            String table = schema.table().name();
            try (ResultSet rs = meta.getPrimaryKeys(catalog, namespace, table)) {
                Map<String, List<KeyColumn>> keys = new LinkedHashMap<>();
                int rows = 0;
                while (rs.next()) {
                    bound(++rows);
                    keys.computeIfAbsent(Objects.requireNonNullElse(rs.getString("PK_NAME"), "PRIMARY"), k -> new ArrayList<>())
                            .add(new KeyColumn(rs.getString("COLUMN_NAME"), rs.getInt("KEY_SEQ")));
                }
                keys.forEach((name, columns) -> primary.add(new KeyInfo(name,
                        columns.stream().sorted(Comparator.comparingInt(KeyColumn::position)).toList())));
            } catch (SQLException e) { warnings.add("主键元数据不可用"); }
            try (ResultSet rs = meta.getImportedKeys(catalog, namespace, table)) {
                Map<String, ForeignKeyInfo> keys = new LinkedHashMap<>();
                int rows = 0;
                while (rs.next()) {
                    bound(++rows);
                    String name = rs.getString("FK_NAME");
                    String key = name == null ? rs.getString("PKTABLE_SCHEM") + "." + rs.getString("PKTABLE_NAME") : name;
                    ForeignKeyInfo item = keys.get(key);
                    if (item == null) {
                        item = new ForeignKeyInfo(name, rs.getString("PKTABLE_CAT"), rs.getString("PKTABLE_SCHEM"),
                                rs.getString("PKTABLE_NAME"), new ArrayList<>(), rs.getInt("UPDATE_RULE"), rs.getInt("DELETE_RULE"));
                        keys.put(key, item);
                    }
                    item.columns().add(new ColumnReference(rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME"), rs.getInt("KEY_SEQ")));
                }
                keys.values().forEach(k -> foreign.add(new ForeignKeyInfo(k.name(), k.referencedCatalog(), k.referencedSchema(),
                        k.referencedTable(), k.columns().stream().sorted(Comparator.comparingInt(ColumnReference::position)).toList(),
                        k.updateRule(), k.deleteRule())));
            } catch (SQLException e) { warnings.add("外键元数据不可用"); }
            try (ResultSet rs = meta.getIndexInfo(catalog, namespace, table, false, true)) {
                Map<String, IndexInfo> values = new LinkedHashMap<>();
                int rows = 0;
                while (rs.next()) {
                    bound(++rows);
                    if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                    String name = rs.getString("INDEX_NAME");
                    if (name == null) continue;
                    IndexInfo item = values.get(name);
                    if (item == null) {
                        item = new IndexInfo(name, !rs.getBoolean("NON_UNIQUE"), new ArrayList<>(), rs.getString("FILTER_CONDITION"));
                        values.put(name, item);
                    }
                    item.columns().add(new IndexColumn(rs.getString("COLUMN_NAME"), rs.getInt("ORDINAL_POSITION"), rs.getString("ASC_OR_DESC")));
                }
                values.values().forEach(k -> indexes.add(new IndexInfo(k.name(), k.unique(),
                        k.columns().stream().sorted(Comparator.comparingInt(IndexColumn::position)).toList(), k.filter())));
            } catch (SQLException e) { warnings.add("索引元数据不可用"); }
        } catch (SQLException e) { warnings.add("约束元数据连接不可用"); }
        return new TableSchema(schema.table(), schema.columns(), List.copyOf(primary), List.copyOf(foreign), List.copyOf(indexes), List.copyOf(warnings));
    }
    private static void bound(int count) throws SQLException {
        if (count > 10000) throw new SQLException("元数据条目超过单表上限");
    }
}
