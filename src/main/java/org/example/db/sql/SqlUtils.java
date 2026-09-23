package org.example.db.sql;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitor;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitor;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitor;
import com.alibaba.druid.sql.dialect.sqlserver.visitor.SQLServerASTVisitor;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.Token;
import org.example.db.datasource.DatabaseType;
import java.util.*;

/** Dialect-aware, fail-closed validation. Only the validated, qualified AST is executed. */
public final class SqlUtils {
    private SqlUtils() {}

    public record CheckedSql(String sql, SqlStatementInfo info, List<TableRef> refs, boolean withoutWhere) {}

    public static CheckedSql check(String sql, DatabaseType type, String defaultSchema,
                                   List<String> allowedSchemas, Set<String> allowedFunctions, int maxLength) {
        if (sql == null || sql.isBlank() || sql.length() > maxLength)
            throw new IllegalArgumentException("SQL 不能为空，且长度不能超过 " + maxLength);
        List<SQLStatement> statements;
        try {
            checkLexicalStructure(sql, dbType(type));
            statements = SQLUtils.parseStatements(sql, dbType(type));
        } catch (RuntimeException | StackOverflowError e) {
            throw new IllegalArgumentException("SQL 无法按当前数据库方言安全解析");
        }
        if (statements.size() != 1) throw new IllegalArgumentException("仅允许单条 SQL");
        SQLStatement statement = statements.getFirst();
        statement.setAfterSemi(false); // JDBC drivers such as Oracle reject a trailing statement terminator.
        SqlStatementInfo info = classify(statement);
        if (info.category() == SqlCategory.OTHER) throw new IllegalArgumentException("不支持此 SQL 类型");
        Guard guard = new Guard(statement, type, defaultSchema, allowedSchemas, allowedFunctions);
        statement.accept(guard);
        boolean withoutWhere = statement instanceof SQLUpdateStatement u && u.getWhere() == null
                || statement instanceof SQLDeleteStatement d && d.getWhere() == null;
        return new CheckedSql(SQLUtils.toSQLString(statement, dbType(type)), info, List.copyOf(guard.refs), withoutWhere);
    }

    private static void checkLexicalStructure(String sql, DbType type) {
        var lexer = SQLParserUtils.createLexer(sql, type);
        lexer.setKeepComments(true);
        int depth = 0;
        do {
            lexer.nextToken();
            if (lexer.token() == Token.ERROR || lexer.token() == Token.HINT)
                throw new IllegalArgumentException("不允许解析错误或执行提示");
            var comments = lexer.readAndResetComments();
            if (comments != null && comments.stream().anyMatch(c -> c.startsWith("/*!") || c.startsWith("/*M!") || c.startsWith("/*+")))
                throw new IllegalArgumentException("不允许可执行注释");
            if (lexer.token() == Token.LPAREN && ++depth > 64) throw new IllegalArgumentException("SQL 嵌套过深");
            if (lexer.token() == Token.RPAREN) depth--;
        } while (lexer.token() != Token.EOF);
    }

    public static DbType dbType(DatabaseType type) {
        return switch (type) {
            case POSTGRESQL -> DbType.postgresql;
            case MYSQL -> DbType.mysql;
            case ORACLE -> DbType.oracle;
            case SQLSERVER -> DbType.sqlserver;
            default -> throw new IllegalArgumentException("无法识别数据库方言");
        };
    }

    private static SqlStatementInfo classify(SQLStatement statement) {
        if (statement instanceof SQLSelectStatement) return new SqlStatementInfo("SELECT", SqlCategory.READ);
        if (statement instanceof SQLInsertStatement) return new SqlStatementInfo("INSERT", SqlCategory.WRITE_DML);
        if (statement instanceof SQLUpdateStatement) return new SqlStatementInfo("UPDATE", SqlCategory.WRITE_DML);
        if (statement instanceof SQLDeleteStatement) return new SqlStatementInfo("DELETE", SqlCategory.WRITE_DML);
        if (statement instanceof SQLMergeStatement) return new SqlStatementInfo("MERGE", SqlCategory.WRITE_DML);
        if (statement instanceof SQLCreateTableStatement) return new SqlStatementInfo("CREATE", SqlCategory.DDL);
        if (statement instanceof SQLAlterTableStatement) return new SqlStatementInfo("ALTER", SqlCategory.DDL);
        if (statement instanceof SQLDropTableStatement) return new SqlStatementInfo("DROP", SqlCategory.DDL);
        if (statement instanceof SQLTruncateStatement) return new SqlStatementInfo("TRUNCATE", SqlCategory.DDL);
        return new SqlStatementInfo("UNSUPPORTED", SqlCategory.OTHER);
    }

    public static String identifier(String raw, DatabaseType type) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("对象名不能为空");
        if (raw.startsWith("\"") && raw.endsWith("\"")) return raw.substring(1, raw.length() - 1).replace("\"\"", "\"");
        String tick = Character.toString(96);
        if (raw.startsWith(tick) && raw.endsWith(tick)) return raw.substring(1, raw.length() - 1).replace(tick + tick, tick);
        if (raw.startsWith("[") && raw.endsWith("]")) return raw.substring(1, raw.length() - 1).replace("]]", "]");
        return switch (type) {
            case POSTGRESQL -> raw.toLowerCase(Locale.ROOT);
            case ORACLE -> raw.toUpperCase(Locale.ROOT);
            default -> raw;
        };
    }

    public static boolean schemaAllowed(String schema, String defaultSchema, List<String> allowed) {
        List<String> effective = allowed == null || allowed.isEmpty() ? List.of(defaultSchema) : allowed;
        // Do not merge distinct quoted schemas or case-sensitive database names.
        return effective.contains("*") || effective.contains(schema);
    }

    private static String quote(String name, DatabaseType type) {
        String tick = Character.toString(96);
        return switch (type) {
            case MYSQL -> tick + name.replace(tick, tick + tick) + tick;
            case SQLSERVER -> "[" + name.replace("]", "]]") + "]";
            default -> "\"" + name.replace("\"", "\"\"") + "\"";
        };
    }

    private static final class Guard extends SQLASTVisitorAdapter
            implements MySqlASTVisitor, OracleASTVisitor, PGASTVisitor, SQLServerASTVisitor {
        private final SQLStatement root;
        private final DatabaseType type;
        private final String defaultSchema;
        private final List<String> allowed;
        private final Set<String> functions;
        private final Set<TableRef> refs = new LinkedHashSet<>();
        private int depth;

        private Guard(SQLStatement root, DatabaseType type, String defaultSchema,
                      List<String> allowed, Set<String> functions) {
            this.root = root;
            this.type = type;
            this.defaultSchema = defaultSchema;
            this.allowed = allowed;
            this.functions = new HashSet<>();
            for (String function : functions) this.functions.add(function.strip().toLowerCase(Locale.ROOT));
        }

        @Override
        public void preVisit(SQLObject node) {
            if (++depth > 128) reject("SQL 嵌套过深");
            if (node instanceof SQLSelectItem item && item.getAlias() != null
                    && Set.of("select", "insert", "update", "delete", "merge", "create", "alter", "drop",
                    "truncate", "exec", "execute", "with").contains(item.getAlias().toLowerCase(Locale.ROOT)))
                reject("存在有歧义的关键字别名，请为列名加引号");
            if (node.getClass().getSimpleName().endsWith("Hint")) reject("不允许表提示或执行提示");
            if (node instanceof SQLTableSource source && source.getHints() != null && !source.getHints().isEmpty())
                reject("不允许表提示或执行提示");
            if (node instanceof SQLStatement nested && nested != root && !(nested instanceof SQLSelectStatement))
                reject("不允许嵌套写入或可执行语句");
            if (node instanceof SQLSelectQueryBlock block) {
                if (block.getInto() != null || block.isForUpdate()) reject("不允许 SELECT INTO / 加锁查询");
                if (block instanceof PGSelectQueryBlock pg && pg.getForClause() != null) reject("不允许加锁查询");
                if (block instanceof MySqlSelectQueryBlock mysql && mysql.isLockInShareMode()) reject("不允许加锁查询");
            }
            if (node instanceof SQLMethodInvokeExpr method) validateFunction(method);
            if (node instanceof SQLAggregateExpr aggregate
                    && !functions.contains(aggregate.getMethodName().toLowerCase(Locale.ROOT)))
                reject("聚合函数未在允许列表中: " + aggregate.getMethodName());
            if (node instanceof SQLVariantRefExpr || node instanceof SQLSequenceExpr)
                reject("不允许会话变量、占位符或序列操作");
            if (node instanceof SQLTableSource && !(node instanceof SQLExprTableSource)
                    && !(node instanceof SQLJoinTableSource) && !(node instanceof SQLSubqueryTableSource)
                    && !(node instanceof SQLUnionQueryTableSource) && !(node instanceof SQLWithSubqueryClause.Entry))
                reject("不支持此表来源（表函数、远程对象等）");
            if (node instanceof SQLAlterTableItem) {
                String kind = node.getClass().getSimpleName();
                if (!Set.of("SQLAlterTableAddColumn", "SQLAlterTableDropColumnItem", "SQLAlterTableAlterColumn",
                        "SQLAlterTableModifyColumn", "SQLAlterTableAddConstraint", "SQLAlterTableDropConstraint",
                        "SQLAlterTableDropIndex", "SQLAlterTableAddIndex").contains(kind))
                    reject("暂不支持此 ALTER TABLE 操作: " + kind);
            }
            if (node instanceof SQLExprTableSource table) validateTable(table);
        }

        @Override
        public void postVisit(SQLObject node) { depth--; }

        private String validateFunction(SQLMethodInvokeExpr method) {
            String name = method.getMethodName().toLowerCase(Locale.ROOT);
            if (method.getOwner() == null && functions.contains(name)) return name;
            if (method.getOwner() != null) {
                String owner = method.getOwner().toString().toLowerCase(Locale.ROOT);
                if (type == DatabaseType.POSTGRESQL && owner.equals("pg_catalog") && functions.contains(name))
                    return name;
                if (type == DatabaseType.ORACLE && (owner.equals("dbms_lob") || owner.equals("sys.dbms_lob"))
                        && Set.of("substr", "getlength", "instr").contains(name)
                        && functions.contains("dbms_lob." + name))
                    return "dbms_lob." + name;
                name = owner + "." + name;
            }
            reject("函数未在允许列表中: " + name);
            return name;
        }

        private boolean isQueryTableFunction(SQLMethodInvokeExpr method) {
            String name = validateFunction(method);
            return switch (type) {
                case POSTGRESQL -> Set.of("string_to_table", "regexp_split_to_table",
                        "jsonb_path_query", "jsonb_path_query_tz").contains(name);
                case SQLSERVER -> name.equals("string_split");
                default -> false;
            };
        }

        private void validateTable(SQLExprTableSource source) {
            SQLExpr expr = source.getExpr();
            if (expr instanceof SQLMethodInvokeExpr method) {
                if (!(root instanceof SQLSelectStatement) || !isQueryTableFunction(method))
                    reject("查询表来源仅支持已允许的文本拆分和 JSONPath 查询函数");
                // Keep the function and visit all its arguments. They may contain subqueries
                // whose physical tables must still be checked and schema-qualified.
                return;
            }
            String schema = defaultSchema;
            String table;
            if (expr instanceof SQLIdentifierExpr id) {
                table = identifier(id.getName(), type);
                if (isCte(source, table)) return;
                SQLTableSource from = root instanceof SQLUpdateStatement update ? update.getFrom()
                        : root instanceof SQLDeleteStatement delete ? delete.getFrom() : null;
                SQLTableSource target = root instanceof SQLUpdateStatement update ? update.getTableSource()
                        : root instanceof SQLDeleteStatement delete ? delete.getTableSource() : null;
                if (type == DatabaseType.SQLSERVER && source == target && from != null
                        && from != source && from.findTableSource(id.getName()) != null) return;
                if (table.equalsIgnoreCase("dual") && (type == DatabaseType.ORACLE || type == DatabaseType.MYSQL)
                        && root instanceof SQLSelectStatement) return;
            } else if (expr instanceof SQLPropertyExpr property && property.getOwner() instanceof SQLIdentifierExpr owner) {
                schema = identifier(owner.getName(), type);
                table = identifier(property.getName(), type);
            } else {
                reject("仅允许 table 或 schema.table；不允许跨 catalog、数据库链接或表函数");
                return;
            }
            if (!schemaAllowed(schema, defaultSchema, allowed)) reject("不允许访问 schema: " + schema);
            refs.add(new TableRef(schema, table));
            source.setExpr(new SQLPropertyExpr(new SQLIdentifierExpr(quote(schema, type)), quote(table, type)));
        }

        private boolean isCte(SQLObject node, String name) {
            SQLWithSubqueryClause.Entry currentEntry = null;
            for (SQLObject parent = node.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof SQLWithSubqueryClause.Entry entry) currentEntry = entry;
                SQLWithSubqueryClause with = null;
                if (parent instanceof SQLSelect select) with = select.getWithSubQuery();
                else if (parent instanceof SQLUpdateStatement update) with = update.getWith();
                else if (parent instanceof SQLDeleteStatement delete) with = delete.getWith();
                else if (parent instanceof SQLInsertStatement insert) with = insert.getWith();
                if (with == null) continue;
                for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                    boolean self = entry == currentEntry;
                    if (self && !Boolean.TRUE.equals(with.getRecursive()) && type != DatabaseType.SQLSERVER) break;
                    if (identifier(entry.getAlias(), type).equals(name)) return true;
                    if (self) break;
                }
            }
            return false;
        }

        private static void reject(String message) { throw new IllegalArgumentException(message); }
    }
}
