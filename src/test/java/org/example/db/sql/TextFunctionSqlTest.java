package org.example.db.sql;

import org.example.db.config.DbExplorerProperties;
import org.example.db.datasource.DatabaseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class TextFunctionSqlTest {
    private SqlUtils.CheckedSql check(String sql, DatabaseType type) {
        return SqlUtils.check(sql, type, "public", List.of("public"), new DbExplorerProperties().getAllowedFunctions(), 65536);
    }

    static Stream<Arguments> scalarQueries() {
        return Stream.of(
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT left(body, 4000), right(body, 4000), char_length(body), octet_length(body) FROM documents"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT substring(body FROM 100001 FOR 4000), position('keyword' IN body), strpos(body, 'keyword') FROM documents"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT split_part(body, chr(10), 2), concat_ws('|', left(body, 20), right(body, 20)) FROM documents"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT regexp_replace(body, '[[:space:]]+', ' ', 'g'), regexp_substr(body, 'keyword.{0,100}'), regexp_count(body, 'keyword'), regexp_instr(body, 'keyword') FROM documents"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT translate(btrim(body), chr(13), ''), overlay(body, 'x', 1, 1), initcap(body), reverse(body) FROM documents"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT pg_catalog.left(body, 4000), pg_catalog.length(body) FROM documents"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT array_to_string(regexp_split_to_array(body, ','), '|'), array_to_string(string_to_array(body, ','), '|') FROM documents"),
                Arguments.of(DatabaseType.MYSQL, "SELECT LEFT(body, 4000), RIGHT(body, 4000), CHARACTER_LENGTH(body), OCTET_LENGTH(body), MID(body, 100001, 4000) FROM documents"),
                Arguments.of(DatabaseType.MYSQL, "SELECT SUBSTRING_INDEX(body, CHAR(10), 3), LOCATE('keyword', body), INSTR(body, 'keyword'), CONCAT_WS('|', 'a', 'b') FROM documents"),
                Arguments.of(DatabaseType.MYSQL, "SELECT REGEXP_REPLACE(body, '[[:space:]]+', ' '), REGEXP_SUBSTR(body, 'keyword.{0,100}'), REGEXP_INSTR(body, 'keyword'), REGEXP_LIKE(body, 'keyword') FROM documents"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT LEN(body), DATALENGTH(body), LEFT(body, 4000), RIGHT(body, 4000), SUBSTRING(body, 100001, 4000) FROM documents"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT CHARINDEX('keyword', body), PATINDEX('%keyword%', body), STUFF(body, 1, 1, 'x'), CONCAT_WS('|', LEFT(body, 20), RIGHT(body, 20)) FROM documents"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT REPLACE(body, CHAR(13) + CHAR(10), CHAR(10)), STRING_ESCAPE(body, 'json'), UNICODE(body), NCHAR(10) FROM documents"),
                Arguments.of(DatabaseType.ORACLE, "SELECT LENGTHB(body), LENGTHC(body), INSTR(body, 'keyword'), REGEXP_SUBSTR(body, 'keyword.{0,100}'), REGEXP_REPLACE(body, '[[:space:]]+', ' ') FROM public.documents"),
                Arguments.of(DatabaseType.ORACLE, "SELECT DBMS_LOB.GETLENGTH(body), DBMS_LOB.SUBSTR(body, 4000, 100001), DBMS_LOB.INSTR(body, 'keyword') FROM public.documents"),
                Arguments.of(DatabaseType.ORACLE, "SELECT SYS.DBMS_LOB.SUBSTR(body, 4000, 1), SYS.DBMS_LOB.GETLENGTH(body), SYS.DBMS_LOB.INSTR(body, 'keyword') FROM public.documents")
        );
    }

    @ParameterizedTest @MethodSource("scalarQueries")
    void acceptsTextProcessingAndPreservesUnderlyingTableValidation(DatabaseType type, String sql) {
        String schema = type == DatabaseType.ORACLE ? "PUBLIC" : "public";
        var result = SqlUtils.check(sql, type, schema, List.of(schema), new DbExplorerProperties().getAllowedFunctions(), 65536);
        assertThat(result.info().category()).isEqualTo(SqlCategory.READ);
        assertThat(result.refs()).containsExactly(new TableRef(schema, type == DatabaseType.ORACLE ? "DOCUMENTS" : "documents"));
        var reparsed = SqlUtils.check(result.sql(), type, schema, List.of(schema), new DbExplorerProperties().getAllowedFunctions(), 65536);
        assertThat(reparsed.refs()).isEqualTo(result.refs());
    }

    static Stream<Arguments> tableQueries() {
        return Stream.of(
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT part FROM documents d, string_to_table(d.body, chr(10)) AS parts(part)"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT part FROM documents d, regexp_split_to_table(d.body, '[[:space:]]+') AS parts(part)"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT part FROM documents d, pg_catalog.string_to_table(d.body, ',') AS parts(part)"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT s.value FROM documents d CROSS APPLY STRING_SPLIT(d.body, CHAR(10)) s"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT s.value, s.ordinal FROM documents d OUTER APPLY STRING_SPLIT(d.body, CHAR(10), 1) s")
        );
    }

    @ParameterizedTest @MethodSource("tableQueries")
    void textSplittingFunctionsAreNotTreatedAsPhysicalTables(DatabaseType type, String sql) {
        var result = check(sql, type);
        assertThat(result.refs()).containsExactly(new TableRef("public", "documents"));
        assertThat(check(result.sql(), type).refs()).isEqualTo(result.refs());
    }

    static Stream<Arguments> rejectedQueries() {
        return Stream.of(
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT LEFT(pg_read_file('/etc/passwd'), 4000)"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT pg_catalog.pg_read_file('/etc/passwd')"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT public.left(body, 20) FROM documents"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT * FROM public.string_to_table('a,b', ',')"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT * FROM string_to_table((SELECT body FROM secret.documents), ',')"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT * FROM regexp_split_to_table(pg_read_file('/etc/passwd'), ',')"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT * FROM length('abc')"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT s.value FROM secret.documents d CROSS APPLY STRING_SPLIT(d.body, ',') s"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT * FROM dbo.STRING_SPLIT('a,b', ',')"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT * FROM OPENROWSET(BULK 'file.txt', SINGLE_CLOB) AS x"),
                Arguments.of(DatabaseType.ORACLE, "SELECT DBMS_LOB.TRIM(body, 10) FROM public.documents"),
                Arguments.of(DatabaseType.ORACLE, "SELECT SYS.DBMS_LOB.WRITE(body, 1, 1, 'x') FROM public.documents"),
                Arguments.of(DatabaseType.ORACLE, "SELECT other.DBMS_LOB.SUBSTR(body, 20, 1) FROM public.documents"),
                Arguments.of(DatabaseType.MYSQL, "SELECT LEFT(LOAD_FILE('/etc/passwd'), 20)"),
                Arguments.of(DatabaseType.MYSQL, "SELECT * FROM string_split('a,b', ',')")
        );
    }

    @ParameterizedTest @MethodSource("rejectedQueries")
    void textFunctionsDoNotBypassOtherSqlChecks(DatabaseType type, String sql) {
        String schema = type == DatabaseType.ORACLE ? "PUBLIC" : "public";
        assertThatThrownBy(() -> SqlUtils.check(sql, type, schema, List.of(schema),
                new DbExplorerProperties().getAllowedFunctions(), 65536)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void customFunctionListStillControlsTextFunctionsAndIgnoresConfigurationCase() {
        var functions = Set.of(" LEFT ", "LENGTH");
        assertThatCode(() -> SqlUtils.check("SELECT LEFT(body, 20), pg_catalog.length(body) FROM documents",
                DatabaseType.POSTGRESQL, "public", List.of("public"), functions, 65536)).doesNotThrowAnyException();
        assertThatThrownBy(() -> SqlUtils.check("SELECT RIGHT(body, 20) FROM documents",
                DatabaseType.POSTGRESQL, "public", List.of("public"), functions, 65536)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlUtils.check("SELECT * FROM string_to_table('a,b', ',')",
                DatabaseType.POSTGRESQL, "public", List.of("public"), functions, 65536)).isInstanceOf(IllegalArgumentException.class);
    }
}
