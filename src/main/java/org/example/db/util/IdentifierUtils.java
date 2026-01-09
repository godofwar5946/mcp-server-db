package org.example.db.util;

import java.util.regex.Pattern;

/**
 * SQL 标识符（schema/table/column）相关工具。
 * <p>
 * 说明：
 * <ul>
 *   <li>本项目“执行 SQL”是直接执行用户输入的 SQL，因此标识符校验主要用于：schema 白名单/表结构缓存 key 的安全性与一致性</li>
 *   <li>元数据查询（information_schema / sys / pg_catalog 等）均使用参数化查询，不会把 schema/table 拼接进 SQL 字符串</li>
 * </ul>
 */
public final class IdentifierUtils {

    /**
     * 仅允许：字母/下划线开头，后续字母/数字/下划线/$/#。
     * <p>
     * 该规则覆盖多数数据库“未加引号”的命名习惯：
     * <ul>
     *   <li>PostgreSQL：常见 public、table_name</li>
     *   <li>MySQL：常见 db_name、table_name</li>
     *   <li>Oracle：对象名常见大写，且部分系统对象带 $/#</li>
     *   <li>SQLServer：dbo、TableName 等</li>
     * </ul>
     * 如果你的对象名包含空格/连字符等特殊字符，建议使用更完整的“白名单 + 方言转义”方案。
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$#]*$");

    private IdentifierUtils() {
    }

    /**
     * 校验并返回原值；失败则抛异常。
     */
    public static String requireSafeIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "不合法（仅允许 字母/数字/下划线/$/#，且不能以数字开头）: " + value);
        }
        return value;
    }

    /**
     * PostgreSQL 风格的安全引号：使用双引号包裹。
     * <p>
     * 注意：不同数据库的“标识符引号”不同（MySQL 常用反引号，SQLServer 常用中括号），
     * 本项目目前仅在必要时使用该方法（且入参需通过 {@link #requireSafeIdentifier} 校验）。
     */
    public static String quoteIdentifier(String identifier) {
        requireSafeIdentifier(identifier, "identifier");
        return "\"" + identifier + "\"";
    }
}

