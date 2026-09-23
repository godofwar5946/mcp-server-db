package org.example.db.model;
import java.util.List;

public record TableSchema(TableInfo table, List<ColumnInfo> columns, List<KeyInfo> primaryKeys,
                          List<ForeignKeyInfo> foreignKeys, List<IndexInfo> indexes, List<String> metadataWarnings) {
    public TableSchema(TableInfo table, List<ColumnInfo> columns) {
        this(table, columns, List.of(), List.of(), List.of(), List.of());
    }
    public record KeyInfo(String name, List<KeyColumn> columns) {}
    public record KeyColumn(String name, int position) {}
    public record ForeignKeyInfo(String name, String referencedCatalog, String referencedSchema, String referencedTable,
                                 List<ColumnReference> columns, int updateRule, int deleteRule) {}
    public record ColumnReference(String column, String referencedColumn, int position) {}
    public record IndexInfo(String name, boolean unique, List<IndexColumn> columns, String filter) {}
    public record IndexColumn(String name, int position, String direction) {}
}
