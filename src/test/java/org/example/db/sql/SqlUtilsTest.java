package org.example.db.sql;

import org.example.db.config.DbExplorerProperties;
import org.example.db.datasource.DatabaseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class SqlUtilsTest {
    private SqlUtils.CheckedSql check(String sql, DatabaseType type, String schema) {
        return SqlUtils.check(sql, type, schema, List.of(schema), new DbExplorerProperties().getAllowedFunctions(), 65536);
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT 1 -- '\n; DELETE FROM public.users"),
                Arguments.of(DatabaseType.POSTGRESQL, "WITH d AS (DELETE FROM public.users RETURNING id) SELECT * FROM d"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT * INTO public.copy FROM public.users"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT * FROM public.users FOR UPDATE"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT nextval('public.seq')"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT set_config('search_path', 'secret', false)"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT (SELECT id FROM secret.users)"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT * FROM public.users a, secret.users b"),
                Arguments.of(DatabaseType.POSTGRESQL, "WITH x AS (SELECT * FROM secret.users) SELECT * FROM x"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT * FROM \"Public\".users"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT * FROM foreign_db.public.users"),
                Arguments.of(DatabaseType.POSTGRESQL, "SELECT * FROM public.users; -- harmless trailing comment\n DELETE FROM public.users"),
                Arguments.of(DatabaseType.MYSQL, "SELECT * FROM public.users INTO OUTFILE '/tmp/review'"),
                Arguments.of(DatabaseType.MYSQL, "SELECT /*!50000 SLEEP(5) */ 1"),
                Arguments.of(DatabaseType.MYSQL, "SELECT * FROM public.users LOCK IN SHARE MODE"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT 1 DELETE FROM public.users"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT * INTO public.copy FROM public.users"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT * FROM otherdb.public.users"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT NEXT VALUE FOR public.seq"),
                Arguments.of(DatabaseType.SQLSERVER, "SELECT * FROM public.users WITH (UPDLOCK)"),
                Arguments.of(DatabaseType.ORACLE, "SELECT public.seq.NEXTVAL FROM dual"),
                Arguments.of(DatabaseType.ORACLE, "SELECT * FROM public.users@remote"),
                Arguments.of(DatabaseType.POSTGRESQL, "ALTER TABLE public.users SET SCHEMA secret"),
                Arguments.of(DatabaseType.POSTGRESQL, "CREATE FUNCTION public.f() RETURNS int AS 'SELECT 1' LANGUAGE SQL")
        );
    }

    @ParameterizedTest @MethodSource("rejected")
    void rejectsUnsafeOrUnverifiableSql(DatabaseType type, String sql) {
        assertThatThrownBy(() -> check(sql, type, "public")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @EnumSource(value=DatabaseType.class, names={"POSTGRESQL","MYSQL","ORACLE","SQLSERVER"})
    void qualifiesAllNestedAndJoinedTables(DatabaseType type) {
        String schema = type == DatabaseType.ORACLE ? "PUBLIC" : "public";
        var result = check("SELECT u.id FROM users u JOIN orders o ON u.id=o.user_id WHERE EXISTS (SELECT 1 FROM payments p WHERE p.user_id=u.id)", type, schema);
        assertThat(result.refs()).hasSize(3).allMatch(ref -> ref.schema().equals(schema));
        assertThat(result.info().category()).isEqualTo(SqlCategory.READ);
    }

    @Test void handlesCteScopeAndKeepsRealTables() {
        var result = check("WITH users AS (SELECT id FROM users) SELECT * FROM users", DatabaseType.POSTGRESQL, "public");
        assertThat(result.refs()).containsExactly(new TableRef("public", "users"));
        assertThat(result.sql()).contains("\"public\".\"users\"");
        var nested = check("WITH x AS (SELECT id FROM public.users), y AS (SELECT * FROM x) SELECT * FROM y", DatabaseType.POSTGRESQL, "public");
        assertThat(nested.refs()).containsExactly(new TableRef("public", "users"));
    }

    @ParameterizedTest @ValueSource(strings={"ALTER TABLE public.users ADD note varchar(40)", "DROP TABLE public.users", "TRUNCATE TABLE public.users", "CREATE TABLE public.users (id int)"})
    void extractsDdlTargets(String sql) {
        var result = check(sql, DatabaseType.POSTGRESQL, "public");
        assertThat(result.info().category()).isEqualTo(SqlCategory.DDL);
        assertThat(result.refs()).contains(new TableRef("public", "users"));
    }

    @Test void detectsRealWhereClausesAndAllowsCommentsAndQuotedSemicolons() {
        assertThat(check("UPDATE public.users SET name=' where '", DatabaseType.POSTGRESQL, "public").withoutWhere()).isTrue();
        assertThat(check("DELETE FROM public.users\nWHERE id=1", DatabaseType.POSTGRESQL, "public").withoutWhere()).isFalse();
        assertThat(check("SELECT ';' AS text; -- trailing comment", DatabaseType.POSTGRESQL, "public").info().category()).isEqualTo(SqlCategory.READ);
        assertThat(check("SELECT '/*! a literal */' AS text", DatabaseType.MYSQL, "public").info().category()).isEqualTo(SqlCategory.READ);
        assertThat(check("SELECT 1 FROM dual;", DatabaseType.ORACLE, "PUBLIC").sql()).doesNotEndWith(";");
    }

    @Test void sqlServerUpdateAliasDoesNotSkipValidatingOrQualifyingFromTables() {
        var result = check("UPDATE u SET name='x' FROM public.users u JOIN other o ON u.id=o.id WHERE u.id=1",
                DatabaseType.SQLSERVER, "public");
        assertThat(result.refs()).containsExactlyInAnyOrder(new TableRef("public", "users"), new TableRef("public", "other"));
        assertThat(result.sql()).contains("[public].[other]");
        assertThatThrownBy(() -> check("UPDATE u SET name='x' FROM secret.users u WHERE u.id=1",
                DatabaseType.SQLSERVER, "public")).isInstanceOf(IllegalArgumentException.class);
    }
}
