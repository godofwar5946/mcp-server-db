package org.example.db.sql;

import org.example.db.config.DbExplorerProperties;
import org.example.db.datasource.DatabaseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class JsonPathFunctionSqlTest {
    private SqlUtils.CheckedSql check(String sql) {
        return SqlUtils.check(sql, DatabaseType.POSTGRESQL, "public", List.of("public"),
                new DbExplorerProperties().getAllowedFunctions(), 65536);
    }

    static Stream<Arguments> functionCalls() {
        return Stream.of("jsonb_path_exists", "jsonb_path_match", "jsonb_path_query",
                        "jsonb_path_query_array", "jsonb_path_query_first")
                .flatMap(name -> Stream.of(name, name + "_tz"))
                .flatMap(name -> Stream.of("", "pg_catalog.").map(prefix -> Arguments.of(prefix + name)));
    }

    @ParameterizedTest @MethodSource("functionCalls")
    void acceptsAllBuiltinJsonPathFunctionsWithOptionalVariablesAndSilentFlag(String name) {
        String path = name.contains("match") ? "exists($.items[*] ? (@.score >= $min))" : "$.items[*] ? (@.score >= $min)";
        var result = check("SELECT " + name + "(body::jsonb, '" + path
                + "'::jsonpath, '{\"min\": 10}'::jsonb, true) AS extracted FROM documents");
        assertThat(result.info().category()).isEqualTo(SqlCategory.READ);
        assertThat(result.refs()).containsExactly(new TableRef("public", "documents"));
        assertThat(check(result.sql()).refs()).isEqualTo(result.refs());
    }

    @ParameterizedTest @ValueSource(strings = {"jsonb_path_query", "jsonb_path_query_tz",
            "pg_catalog.jsonb_path_query", "pg_catalog.jsonb_path_query_tz"})
    void acceptsJsonPathRowExtractionWithoutTreatingTheFunctionAsATable(String function) {
        var result = check("SELECT d.id, item.value FROM documents d, " + function
                + "(d.body::jsonb, '$.items[*]') AS item(value) WHERE d.id=1 LIMIT 20");
        assertThat(result.refs()).containsExactly(new TableRef("public", "documents"));
        assertThat(check(result.sql()).refs()).isEqualTo(result.refs());
        assertThat(result.sql()).contains(function);
    }

    @Test void canExtractTextAndSliceItBeforeReturningTheResult() {
        var result = check("SELECT SUBSTRING(jsonb_path_query_first(body::jsonb, '$.content') #>> '{}', 100001, 4000) AS chunk FROM documents");
        assertThat(result.info().category()).isEqualTo(SqlCategory.READ);
        assertThat(check(result.sql()).refs()).containsExactly(new TableRef("public", "documents"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "SELECT * FROM jsonb_path_query((SELECT body FROM secret.documents), '$.items[*]')",
            "SELECT jsonb_path_query_first(body, '$.content') FROM secret.documents",
            "SELECT jsonb_path_query_first(pg_read_file('/etc/passwd')::jsonb, '$')",
            "SELECT * FROM public.jsonb_path_query('{}', '$')",
            "SELECT jsonb_path_custom(body, '$') FROM documents",
            "SELECT * FROM jsonb_path_query_evil('{}', '$')"
    })
    void jsonPathFunctionsDoNotBypassSchemaOrFunctionChecks(String sql) {
        assertThatThrownBy(() -> check(sql)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void customAllowlistCanStillDisableJsonPathFunctions() {
        assertThatThrownBy(() -> SqlUtils.check("SELECT jsonb_path_query_first(body, '$.content') FROM documents",
                DatabaseType.POSTGRESQL, "public", List.of("public"), Set.of("substring"), 65536))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("函数未在允许列表中");
        assertThatThrownBy(() -> SqlUtils.check("SELECT * FROM jsonb_path_query('{}', '$')",
                DatabaseType.POSTGRESQL, "public", List.of("public"), Set.of("substring"), 65536))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("函数未在允许列表中");
    }
}
