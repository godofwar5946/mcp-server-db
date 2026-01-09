package org.example.db.sql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 字符串分析工具（轻量级、够用即可）。
 * <p>
 * 注意：
 * <ul>
 *   <li>这里不是完整 SQL Parser，只做“安全守卫 + 表名提取”的最小实现。</li>
 *   <li>复杂 SQL（多层子查询、函数表、动态 SQL）可能提取不完整，但不影响执行。</li>
 * </ul>
 */
public final class SqlUtils {

    private SqlUtils() {
    }

    /**
     * 是否包含多条语句（通过分号 ; 判断，且忽略引号内的分号）。
     * <p>
     * 出于安全考虑，本项目默认只允许单条语句：避免一次确认执行多条写入。
     */
    public static boolean hasMultipleStatements(String sql) {
        if (sql == null) {
            return false;
        }

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        String dollarDelimiter = null; // PostgreSQL $$ 或 $tag$

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            // Dollar-Quoted 字符串（只在不处于其他引号内时识别）
            if (!inSingleQuote && !inDoubleQuote) {
                if (dollarDelimiter == null && c == '$') {
                    String delimiter = tryReadDollarDelimiter(sql, i);
                    if (delimiter != null) {
                        dollarDelimiter = delimiter;
                        i += delimiter.length() - 1;
                        continue;
                    }
                } else if (dollarDelimiter != null && sql.startsWith(dollarDelimiter, i)) {
                    int delimiterLen = dollarDelimiter.length();
                    dollarDelimiter = null;
                    i += delimiterLen - 1;
                    continue;
                }
            }
            if (dollarDelimiter != null) {
                continue;
            }

            // 单引号字符串
            if (!inDoubleQuote && c == '\'') {
                if (inSingleQuote) {
                    // 处理转义：'' 表示单引号
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                        continue;
                    }
                    inSingleQuote = false;
                } else {
                    inSingleQuote = true;
                }
                continue;
            }

            // 双引号标识符
            if (!inSingleQuote && c == '"') {
                if (inDoubleQuote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                        i++;
                        continue;
                    }
                    inDoubleQuote = false;
                } else {
                    inDoubleQuote = true;
                }
                continue;
            }

            if (!inSingleQuote && !inDoubleQuote && c == ';') {
                // 分号后还有非空白字符 -> 认为是多语句
                for (int j = i + 1; j < sql.length(); j++) {
                    if (!Character.isWhitespace(sql.charAt(j))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 粗略判定 SQL 的主关键字与类别。
     */
    public static SqlStatementInfo classify(String sql) {
        String mainKeyword = findMainKeyword(sql);
        if (mainKeyword == null) {
            return new SqlStatementInfo("UNKNOWN", SqlCategory.OTHER);
        }
        String kw = mainKeyword.toUpperCase(Locale.ROOT);
        return switch (kw) {
            case "SELECT" -> new SqlStatementInfo(kw, SqlCategory.READ);
            case "INSERT", "UPDATE", "DELETE", "MERGE" -> new SqlStatementInfo(kw, SqlCategory.WRITE_DML);
            case "CREATE", "ALTER", "DROP", "TRUNCATE" -> new SqlStatementInfo(kw, SqlCategory.DDL);
            default -> new SqlStatementInfo(kw, SqlCategory.OTHER);
        };
    }

    /**
     * 从 SQL 中提取可能涉及的表名（schema 可选）。
     * <p>
     * 仅作为“预加载表结构缓存”的辅助，提取不完整不会影响 SQL 执行。
     */
    public static List<TableRef> extractTableRefs(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }

        String normalized = sql;
        Set<TableRef> result = new LinkedHashSet<>();

        int len = normalized.length();
        int parenDepth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        String dollarDelimiter = null;

        for (int i = 0; i < len; i++) {
            char c = normalized.charAt(i);

            // 处理 Dollar-Quoted
            if (!inSingle && !inDouble) {
                if (dollarDelimiter == null && c == '$') {
                    String delimiter = tryReadDollarDelimiter(normalized, i);
                    if (delimiter != null) {
                        dollarDelimiter = delimiter;
                        i += delimiter.length() - 1;
                        continue;
                    }
                } else if (dollarDelimiter != null && normalized.startsWith(dollarDelimiter, i)) {
                    int delimiterLen = dollarDelimiter.length();
                    dollarDelimiter = null;
                    i += delimiterLen - 1;
                    continue;
                }
            }
            if (dollarDelimiter != null) {
                continue;
            }

            if (!inDouble && c == '\'') {
                if (inSingle) {
                    if (i + 1 < len && normalized.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inSingle = false;
                    }
                } else {
                    inSingle = true;
                }
                continue;
            }

            if (!inSingle && c == '"') {
                if (inDouble) {
                    if (i + 1 < len && normalized.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        inDouble = false;
                    }
                } else {
                    inDouble = true;
                }
                continue;
            }

            if (inSingle || inDouble) {
                continue;
            }

            if (c == '(') {
                parenDepth++;
                continue;
            }
            if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
                continue;
            }

            if (parenDepth != 0) {
                continue;
            }

            // 在顶层（parenDepth=0）查找关键字：FROM/JOIN/UPDATE/INTO/DELETE FROM
            if (isWordAt(normalized, i, "FROM")) {
                i = readTableAfterKeyword(normalized, i + 4, result);
            } else if (isWordAt(normalized, i, "JOIN")) {
                i = readTableAfterKeyword(normalized, i + 4, result);
            } else if (isWordAt(normalized, i, "UPDATE")) {
                i = readTableAfterKeyword(normalized, i + 6, result);
            } else if (isWordAt(normalized, i, "INTO")) {
                i = readTableAfterKeyword(normalized, i + 4, result);
            } else if (isWordAt(normalized, i, "DELETE")) {
                // DELETE FROM t ... -> 这里跳过 DELETE 后可能的 FROM
                int j = skipSpaces(normalized, i + 6);
                if (isWordAt(normalized, j, "FROM")) {
                    i = readTableAfterKeyword(normalized, j + 4, result);
                }
            }
        }

        return new ArrayList<>(result);
    }

    private static int readTableAfterKeyword(String sql, int indexAfterKeyword, Set<TableRef> out) {
        int i = skipSpaces(sql, indexAfterKeyword);

        // PostgreSQL: FROM ONLY table
        if (isWordAt(sql, i, "ONLY")) {
            i = skipSpaces(sql, i + 4);
        }

        // 子查询：FROM (SELECT ...) alias -> 不提取
        if (i < sql.length() && sql.charAt(i) == '(') {
            return i;
        }

        // 读取 schema.table 或 table
        IdentifierToken firstToken = readIdentifierToken(sql, i);
        if (firstToken == null) {
            return i;
        }

        List<String> parts = new ArrayList<>();
        parts.add(firstToken.value());
        i += firstToken.rawLength();

        while (true) {
            int beforeDot = i;
            i = skipSpaces(sql, i);
            if (i >= sql.length() || sql.charAt(i) != '.') {
                i = beforeDot;
                break;
            }
            i++; // consume '.'
            IdentifierToken nextToken = readIdentifierToken(sql, i);
            if (nextToken == null) {
                i = beforeDot;
                break;
            }
            parts.add(nextToken.value());
            i += nextToken.rawLength();
            // 限制最多识别 4 段，避免解析过深
            if (parts.size() >= 4) {
                break;
            }
        }

        String schema = null;
        String table;
        if (parts.size() == 1) {
            table = parts.get(0);
        } else if (parts.size() == 2) {
            schema = parts.get(0);
            table = parts.get(1);
        } else {
            // 三段及以上：取最后两段作为 schema.table（常见于 SQLServer 的 db.schema.table）
            schema = parts.get(parts.size() - 2);
            table = parts.get(parts.size() - 1);
        }

        out.add(new TableRef(schema, table));
        return i;
    }

    private static String findMainKeyword(String sql) {
        if (sql == null) {
            return null;
        }
        String s = stripLeadingComments(sql).trim();
        if (s.isEmpty()) {
            return null;
        }

        String first = readFirstWord(s, 0);
        if (first == null) {
            return null;
        }
        if (!first.equalsIgnoreCase("WITH")) {
            return first;
        }

        // WITH 语句：找到顶层（不在括号/引号内）的第一个 SELECT/INSERT/UPDATE/DELETE/MERGE
        int parenDepth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        String dollarDelimiter = null;

        for (int i = first.length(); i < s.length(); i++) {
            char c = s.charAt(i);

            // Dollar-Quoted
            if (!inSingle && !inDouble) {
                if (dollarDelimiter == null && c == '$') {
                    String delimiter = tryReadDollarDelimiter(s, i);
                    if (delimiter != null) {
                        dollarDelimiter = delimiter;
                        i += delimiter.length() - 1;
                        continue;
                    }
                } else if (dollarDelimiter != null && s.startsWith(dollarDelimiter, i)) {
                    int delimiterLen = dollarDelimiter.length();
                    dollarDelimiter = null;
                    i += delimiterLen - 1;
                    continue;
                }
            }
            if (dollarDelimiter != null) {
                continue;
            }

            if (!inDouble && c == '\'') {
                if (inSingle) {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inSingle = false;
                    }
                } else {
                    inSingle = true;
                }
                continue;
            }

            if (!inSingle && c == '"') {
                if (inDouble) {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        inDouble = false;
                    }
                } else {
                    inDouble = true;
                }
                continue;
            }

            if (inSingle || inDouble) {
                continue;
            }

            if (c == '(') {
                parenDepth++;
                continue;
            }
            if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
                continue;
            }

            if (parenDepth != 0) {
                continue;
            }

            if (isWordAt(s, i, "SELECT")) {
                return "SELECT";
            }
            if (isWordAt(s, i, "INSERT")) {
                return "INSERT";
            }
            if (isWordAt(s, i, "UPDATE")) {
                return "UPDATE";
            }
            if (isWordAt(s, i, "DELETE")) {
                return "DELETE";
            }
            if (isWordAt(s, i, "MERGE")) {
                return "MERGE";
            }
        }

        return null;
    }

    private static String stripLeadingComments(String sql) {
        int i = 0;
        while (i < sql.length()) {
            // skip whitespace
            while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
                i++;
            }
            if (i >= sql.length()) {
                return "";
            }
            // line comment --
            if (sql.startsWith("--", i)) {
                int newline = sql.indexOf('\n', i + 2);
                if (newline < 0) {
                    return "";
                }
                i = newline + 1;
                continue;
            }
            // block comment /* */
            if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return "";
                }
                i = end + 2;
                continue;
            }
            break;
        }
        return sql.substring(i);
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isWordAt(String s, int index, String wordUpper) {
        int end = index + wordUpper.length();
        if (index < 0 || end > s.length()) {
            return false;
        }
        if (!s.regionMatches(true, index, wordUpper, 0, wordUpper.length())) {
            return false;
        }
        boolean leftOk = index == 0 || !Character.isLetterOrDigit(s.charAt(index - 1)) && s.charAt(index - 1) != '_';
        boolean rightOk = end == s.length() || !Character.isLetterOrDigit(s.charAt(end)) && s.charAt(end) != '_';
        return leftOk && rightOk;
    }

    private static String readFirstWord(String s, int i) {
        i = skipSpaces(s, i);
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                i++;
            } else {
                break;
            }
        }
        if (i == start) {
            return null;
        }
        return s.substring(start, i);
    }

    /**
     * 读取一个“标识符 token”（允许未加引号或双引号）。
     * <p>
     * 注意：为了简化，这里只支持不含空格/特殊字符的标识符；复杂标识符会返回 null。
     */
    private static IdentifierToken readIdentifierToken(String s, int i) {
        i = skipSpaces(s, i);
        if (i >= s.length()) {
            return null;
        }
        char c = s.charAt(i);
        if (c == '"') {
            int start = i;
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char cc = s.charAt(i);
                if (cc == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        // "" -> "
                        sb.append('"');
                        i += 2;
                        continue;
                    }
                    // 结束引号
                    i++; // consume ending quote
                    String value = sb.toString();
                    return new IdentifierToken(value, i - start);
                }
                sb.append(cc);
                i++;
            }
            return null; // 引号不配对
        }

        // MySQL: `identifier`
        if (c == '`') {
            int start = i;
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char cc = s.charAt(i);
                if (cc == '`') {
                    // `` -> `
                    if (i + 1 < s.length() && s.charAt(i + 1) == '`') {
                        sb.append('`');
                        i += 2;
                        continue;
                    }
                    i++; // consume ending `
                    return new IdentifierToken(sb.toString(), i - start);
                }
                sb.append(cc);
                i++;
            }
            return null;
        }

        // SQLServer: [identifier]
        if (c == '[') {
            int start = i;
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char cc = s.charAt(i);
                if (cc == ']') {
                    // ]] -> ]
                    if (i + 1 < s.length() && s.charAt(i + 1) == ']') {
                        sb.append(']');
                        i += 2;
                        continue;
                    }
                    i++; // consume ending ]
                    return new IdentifierToken(sb.toString(), i - start);
                }
                sb.append(cc);
                i++;
            }
            return null;
        }

        int start = i;
        while (i < s.length()) {
            char cc = s.charAt(i);
            if (Character.isLetterOrDigit(cc) || cc == '_' || cc == '$' || cc == '#') {
                i++;
                continue;
            }
            break;
        }
        if (i == start) {
            return null;
        }
        return new IdentifierToken(s.substring(start, i), i - start);
    }

    /**
     * 读取 PostgreSQL Dollar-Quoted 的分隔符（例如 $$ 或 $tag$）。
     */
    private static String tryReadDollarDelimiter(String s, int i) {
        int next = s.indexOf('$', i + 1);
        if (next < 0) {
            return null;
        }
        // $tag$ 里 tag 只能是字母/数字/下划线
        for (int k = i + 1; k < next; k++) {
            char c = s.charAt(k);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return null;
            }
        }
        return s.substring(i, next + 1);
    }

    private static int dollarDelimiterLength(String delimiter) {
        return delimiter == null ? 0 : delimiter.length();
    }

    /**
     * 解析到的标识符 token（包含“解析值”与“原始消耗长度”）。
     */
    private record IdentifierToken(String value, int rawLength) {
    }
}
