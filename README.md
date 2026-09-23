# Database MCP Server

基于 Java 21、Spring Boot、Spring AI MCP 和 JDBC 的多数据源数据库服务，支持 PostgreSQL、MySQL、Oracle、SQL Server。支持 Streamable HTTP 和 stdio，保留原有七个工具名称。

## 构建与启动

需要 JDK 21 和 Maven：

~~~powershell
mvn clean verify
$env:DB_URL = 'jdbc:postgresql://localhost:5432/myList?currentSchema=public'
$env:DB_USERNAME = 'mcp_reader'
$env:DB_PASSWORD = '<数据库密码>'
# 可选：设置后，HTTP 调用方必须携带相同密钥；不设置则免密连接。
# $env:MCP_API_KEY = '<至少32字符的随机访问密钥>'
java -jar target/mcp-server-db-1.0-SNAPSHOT.jar
~~~

HTTP 默认地址为 `http://127.0.0.1:8080/mcp`。当 `app.security.clients` 中所有身份的 token 均未配置、为空字符串或仅含空白时，客户端无需提供 `Authorization`。免密调用使用 `anonymous` 身份；所有已配置的数据源均可读取。

只要任意身份配置了非空白 token，就启用 HTTP 鉴权。此时客户端必须为每个请求附带 `Authorization: Bearer <访问密钥>`，包括初始化、工具调用、SSE 和 DELETE；未携带或密钥错误返回 401，其他空密钥身份不会提供免密入口。修改密钥后需重启服务。

Claude Code 免密连接示例：

~~~powershell
claude mcp add --transport http --scope user db http://127.0.0.1:8080/mcp
claude mcp list
~~~

启用密钥后，在添加命令中提供 `--header "Authorization: Bearer <访问密钥>"`，放在服务名称 `db` 之前。若 8080 被其他应用占用，可使用 `--server.port=8081` 启动本服务，并同步修改客户端 URL 的端口。

部署到其他主机时，通过 `--server.address=<监听地址>` 和 `MCP_PORT` 指定监听地址、端口，并在受信任网络或 HTTPS 反向代理后使用。不再设置 Origin 白名单或按客户端分配数据源权限。本实现提供预配置 Bearer 密钥认证，不是 OAuth 授权服务器。

stdio 模式：

~~~powershell
java -jar target/mcp-server-db-1.0-SNAPSHOT.jar --spring.profiles.active=stdio
~~~

由 MCP 客户端启动进程，并为其设置数据库环境变量。stdio 不需要 HTTP 访问密钥；所有已配置数据源均可读取，写入规则与 HTTP 相同。stdout 只输出 MCP JSON，日志写入 stderr，不创建 HTTP 心跳清理器或审批 HTTP 接口。

Spring Boot 不会自动加载普通 `.env` 文件。初始配置使用账号名 `mcp_reader`，不会自动创建数据库账号。

## 数据源与权限

`app.db.data-sources` 的每个条目对应一个数据源 ID。schema 白名单严格区分大小写，避免混淆 PostgreSQL 引号对象或大小写敏感的数据库。未指定 schema 的 SQL 表名会按数据源 `default-schema` 自动补全并加引号，不依赖连接的 search_path。

额外数据源配置示例：

~~~yaml
app:
  db:
    data-sources:
      mysql1:
        type: MYSQL
        url: jdbc:mysql://localhost:3306/example
        username: mcp_reader
        password: ${MYSQL_PASSWORD:}
        driver-class-name: com.mysql.cj.jdbc.Driver
        default-schema: example
        allowed-schemas: [example]
        allow-writes: false
      oracle1:
        type: ORACLE
        url: jdbc:oracle:thin:@localhost:1521/example
        username: MCP_READER
        password: ${ORACLE_PASSWORD:}
        driver-class-name: oracle.jdbc.OracleDriver
        default-schema: APP
        allowed-schemas: [APP]
        allow-writes: false
      sqlserver1:
        type: SQLSERVER
        url: jdbc:sqlserver://localhost:1433;databaseName=example;encrypt=true;trustServerCertificate=false
        username: mcp_reader
        password: ${SQLSERVER_PASSWORD:}
        driver-class-name: com.microsoft.sqlserver.jdbc.SQLServerDriver
        default-schema: dbo
        allowed-schemas: [dbo]
        allow-writes: false
  security:
    clients:
      analyst:
        token: ${ANALYST_MCP_KEY:}
~~~

启用 HTTP 鉴权时，每个可登录身份需要独立的至少 32 字符随机密钥。所有身份均可读取 `app.db.data-sources` 中的全部数据源；写入是否开放只由目标数据源的 `allow-writes` 决定，并要求审批密钥验证。身份仅用于会话归属和审计，待确认 SQL 不能跨身份、跨会话使用。`allowed-schemas` 为空时只允许默认 schema；`*` 表示显式放开全部 schema。

使用数据库自身的最小权限账号。SQL 校验约束直接引用的对象，视图、触发器、外键级联、自定义类型和函数可能间接访问其他对象，最终权限仍由数据库决定。PostgreSQL/MySQL/Oracle 查询使用数据库只读事务；SQL Server 不支持这里使用的 `SET TRANSACTION READ ONLY`，应为查询单独配置只授予 SELECT 权限的数据库账号与数据源。不要使用 postgres、root、sa、SYSTEM 等高权限账号作为日常 MCP 查询账号。

Oracle 连接验证 SQL 自动采用 `SELECT 1 FROM DUAL`，其他数据库默认采用 `SELECT 1`；可通过单数据源的 `validation-query` 覆盖。未设置 type 时优先从 JDBC URL 判断，必要时才读取数据库元数据。

## 工具

| 工具 | 行为 |
| --- | --- |
| `db_list_data_sources` | 列出全部已配置数据源及各自的 allowWrites，不返回连接 URL、用户名和口令 |
| `db_list_tables` | 按 schema、keyword、limit、offset 分页列出表和视图 |
| `db_get_table_schema` | 获取列定义、备注、主键、外键和索引 |
| `db_get_table_schema_batch` | 有界并发获取结构；超时或队列满时逐表返回失败信息 |
| `db_query_sql` | 执行经过方言语法树校验的 SELECT |
| `db_prepare_write_sql` | 返回实际待执行 SQL、token、有效期和提示，不执行变更 |
| `db_confirm_write_sql` | 取消或执行当前身份、当前会话的待确认 SQL |

不要向工具传入 caller、context 或 HTTP 访问密钥；调用身份由传输层注入。写入确认工具的 `approvalKey` 用于审批，已通过独立接口批准时可省略。

结构工具中的 table/schema 参数使用数据库中实际的对象名，包括大小写。例如 Oracle 普通表一般使用大写名称。参数化元数据查询支持名称中的空格、中文等字符。主键/外键/索引读取受驱动能力和数据库权限限制，缺失时在 `metadataWarnings` 中说明。

查询返回的 `columns` 包含原始列标签、唯一结果 key 和 JDBC 类型。两个同名 `id` 列会分别使用 `id` 和 `id_2`，不会覆盖。大字段截断会设置 `limited` 并返回 warning；二进制字段使用 Base64，日期和专有类型转换为脱离连接的值或文本。SQLXML、JSON、数组等通过 JDBC 字符流转换为文本；驱动不支持时返回执行错误，不会把已经关闭的 JDBC 对象交给 JSON 序列化器。

默认查询不重复加载或附带整套表结构。需要更新结构时使用结构工具，或传 `refreshSchema=true`。此时附带的结构数据不计入查询行数据的字节预算。

## 写入确认

所有已配置的数据源均可读取。写入、更新和删除要求目标数据源设置 `allow-writes=true`，并使用正确的 `app.security.approval-key`（环境变量 `MCP_APPROVAL_KEY`）批准。数据源 `allow-writes=false` 时，提供正确密钥也不能写入；未配置审批密钥时不能批准写入。审批密钥至少 32 字符，且不能与 HTTP 访问密钥相同。

执行流程在 HTTP 和 stdio 中相同：

1. 调用 `db_prepare_write_sql`，检查返回的数据源、实际 SQL 和 token。
2. 调用 `db_confirm_write_sql`，传入该 token、`confirm=true` 和正确的 `approvalKey`。
3. 服务端重新校验目标数据源的 allow-writes 和 SQL，原子消费 token 后执行一次。

确认工具参数示例：

~~~json
{
  "token": "<prepare 返回的 token>",
  "confirm": true,
  "approvalKey": "<与服务端 approval-key 一致的密钥>"
}
~~~

仅在服务端配置密钥不会自动批准写入。`confirm=false` 取消时无需提供密钥。错误密钥不批准、不执行 SQL；已执行或已取消的 token 不能重放。查询、数据源列表和表结构工具都不需要审批密钥。

HTTP 模式也可以继续使用独立审批接口。先使用 `X-Approval-Key` 查看实际 SQL，再提交该 SQL 的摘要批准：

~~~powershell
$token = '<prepare 返回的 token>'
$headers = @{ 'X-Approval-Key' = $env:MCP_APPROVAL_KEY }
$preview = Invoke-RestMethod "http://127.0.0.1:8080/admin/sql/$token" -Headers $headers
$preview | Format-List owner, dataSourceId, sql, sqlHash, expiresAt

$body = @{ sqlHash = $preview.sqlHash } | ConvertTo-Json
Invoke-RestMethod "http://127.0.0.1:8080/admin/sql/$token/approval" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
~~~

独立审批不会执行 SQL。批准后，由原身份、原会话调用 confirm，无需再次传入 approvalKey。stdio 使用确认工具的 approvalKey 参数，无需 HTTP 审批接口或客户端 elicitation 弹窗。密钥不写入 SQL 审计日志，也不在工具结果中返回。

SQL 校验仍生效：DDL 需要 `app.db.allow-ddl=true`；默认拒绝没有 WHERE 的 UPDATE/DELETE，确需全表变更时显式设置 `app.db.allow-full-table-write=true`。prepare 返回的 SQL 已补全 schema，只有这份 SQL 可以执行。执行失败的已消费 token 也不能重放，需要重新 prepare。

写入在 JDBC 事务内执行，但 Oracle/MySQL 等数据库的 DDL 可能隐式提交，不能承诺 DDL 可回滚。DDL 执行尝试后会使该数据源全部结构缓存失效，覆盖依赖对象；旧的并发加载结果不会重新成为当前缓存。

## SQL 支持范围

使用 Druid 的 PostgreSQL/MySQL/Oracle/SQL Server 解析器。只执行通过校验并补全 schema 后的语法树输出。解析失败或无法确认的结构会拒绝执行。

支持常见 SELECT、CTE、子查询、JOIN、INSERT/UPDATE/DELETE/MERGE，以及开启 DDL 后的常见建表、删表、清空表和列/约束变更。查询支持下述文本拆分及 JSONPath 表函数。拒绝多语句、嵌套写入 CTE、SELECT INTO/OUTFILE、加锁查询、表提示、可执行注释、会话变量、序列操作、跨 catalog 名称、数据库链接和其他未支持的表函数。

函数采用 `app.db.allowed-functions` 白名单，默认已包含常用聚合、长文本处理、日期和窗口函数，无需为下面的文本函数另加配置。自定义该集合仍会替换默认集合，名称忽略大小写和首尾空白；已有自定义列表时需同步加入需要的函数。存储过程、动态 SQL、函数定义、改表所属 schema、表重命名等不在当前支持范围。

## 长文本查询

函数在数据库中处理完整字段，结果返回后才应用字段长度和总字节数限制。对于十几万字符的文本，先查询长度，再按偏移截取，或直接提取包含关键词的内容；不必把完整原文交给 AI。

| 数据库 | 长度、截取和查找函数示例 | 文本拆成多行 |
| --- | --- | --- |
| PostgreSQL | char_length、left/right、substring、split_part、strpos、regexp_replace/substr/count/instr | string_to_table、regexp_split_to_table |
| MySQL | char_length、left/right、substring、substring_index、locate/instr、regexp_replace/substr/instr | 当前未加入 JSON_TABLE |
| SQL Server | len、datalength、left/right、substring、charindex/patindex、stuff、string_escape | STRING_SPLIT，可用于 CROSS APPLY / OUTER APPLY |
| Oracle | length、substr、instr、regexp_replace/substr、DBMS_LOB.GETLENGTH/SUBSTR/INSTR | 当前未加入 XMLTABLE |

清理和拼接还包括 trim/btrim、replace、translate、concat_ws、chr/char 等，具体函数需目标数据库版本支持。Oracle 仅放开上述三个 DBMS_LOB 读取函数（也支持 SYS.DBMS_LOB 前缀）；PostgreSQL 允许已在列表中的函数使用 pg_catalog 前缀。函数参数里的子查询继续执行 schema 校验。语义和版本要求参考 [PostgreSQL 文本函数](https://www.postgresql.org/docs/current/functions-string.html)、[MySQL 文本函数](https://dev.mysql.com/doc/refman/8.4/en/string-functions.html)、[SQL Server STRING_SPLIT](https://learn.microsoft.com/en-us/sql/t-sql/functions/string-split-transact-sql) 和 [Oracle DBMS_LOB](https://docs.oracle.com/en/database/oracle/oracle-database/19/arpls/DBMS_LOB.html)。

例如 PostgreSQL 从第 100001 个字符开始读取 4000 个字符：

~~~sql
SELECT id, char_length(body) AS total_chars,
       substring(body, 100001, 4000) AS chunk
FROM public.documents
WHERE id = 1;
~~~

分段读取时依次使用 1、4001、8001 等起始位置。MySQL/SQL Server 也可用三参数 SUBSTRING；Oracle CLOB 可用 `DBMS_LOB.SUBSTR(body, 1000, 100001)`，其参数顺序为字段、读取长度、起始位置。

PostgreSQL 按换行拆分并筛选相关片段：

~~~sql
SELECT d.id, parts.part
FROM public.documents d, string_to_table(d.body, chr(10)) AS parts(part)
WHERE d.id = 1 AND parts.part LIKE '%关键词%'
LIMIT 20;
~~~

当前解析器下，PostgreSQL 使用上面的逗号连接写法，显式 `CROSS JOIN LATERAL` 暂不支持；OVERLAY 使用 `overlay(body, 'replacement', start, length)` 的逗号参数形式。SQL Server 可用 `FROM documents d CROSS APPLY STRING_SPLIT(d.body, CHAR(10)) s`。

单字段默认最多返回 16384 字符，超出会设置 `limited` 并给出分段提示。文本函数、拆行函数的返回结果仍受行数和字节数限制。函数放行不改变数据库自身的语法、版本或执行权限要求。

### PostgreSQL JSONPath

默认允许 `jsonb_path_exists`、`jsonb_path_match`、`jsonb_path_query`、`jsonb_path_query_array`、`jsonb_path_query_first` 及其五个 `_tz` 变体，也支持 `pg_catalog` 前缀。其中 `jsonb_path_query` / `jsonb_path_query_tz` 可用于 FROM 中逐行返回匹配项。参数和目标数据库版本要求见 [PostgreSQL JSON 函数文档](https://www.postgresql.org/docs/current/functions-json.html)。

提取 JSON 中的长文本后分段返回：

~~~sql
SELECT id,
       substring(jsonb_path_query_first(body::jsonb, '$.content') #>> '{}', 100001, 4000) AS chunk
FROM public.documents
WHERE id = 1;
~~~

将 JSON 数组匹配项拆成多行：

~~~sql
SELECT d.id, item.value
FROM public.documents d,
     jsonb_path_query(d.body::jsonb, '$.items[*]') AS item(value)
WHERE d.id = 1
LIMIT 20;
~~~

以上写法已通过当前 SQL 解析器和校验器测试，尚未对真实 PostgreSQL 执行联调。已有自定义 `allowed-functions` 时，需要将所需函数合并进自定义列表；没有使用 `jsonb_path_*` 前缀通配放行其他函数。

## 资源和日志

| 配置（app.db 下） | 默认值 | 用途 |
| --- | --- | --- |
| query-max-rows | 500 | 查询结果最大行数，不影响元数据查询 |
| query-timeout-seconds | 30 | Statement / 事务执行超时 |
| query-fetch-size | 100 | JDBC 抓取提示，具体行为取决于驱动 |
| result-max-bytes | 1048576 | 查询 columns/rows 的 JSON 字节预算，不含外层协议、SQL 文本和额外结构 |
| field-max-length | 16384 | 文本字符数 / 二进制字节数上限 |
| sql-max-length | 65536 | SQL 文本长度上限，括号嵌套最多 64 层 |
| pool.max-wait | 10s | 等待池中连接的上限 |
| pool.connect-timeout / socket-timeout | 10s / 35s | Druid 传递给 JDBC 驱动的网络超时 |
| schema-cache-ttl / schema-cache-max-size | 20m / 5000 | 本地 Caffeine 结构缓存 |
| schema-fetch-parallelism / schema-queue-capacity | 4 / 200 | 批量查询线程数与队列容量 |
| schema-batch-max-tables / schema-batch-timeout | 50 / 35s | 批次大小和等待时间 |
| pending-sql-ttl / pending-sql-max-size | 10m / 1000 | 待确认 SQL 有效期和容量 |

批次等待超时后，尚未开始的过期任务跳过数据库访问；已经进入 JDBC 的操作依赖 Statement / 网络超时结束。仅提高连接池大小不会解决慢 SQL 或锁等待。

SQL 审计默认写入 `logs/sql-audit.log`，可用 `LOG_PATH` 指定目录。记录身份、会话、SQL 摘要、token 摘要、结果和耗时，默认不记录 SQL 原文或原始数据库异常；需要原文时显式配置 `audit-include-sql=true`。每个文件最大 100 MB，最多保留 30 天，总容量 1 GB。

只公开简要 `/actuator/health`，不公开 env、loggers、threaddump。健康检查表示应用存活，不主动探测所有数据库；备用数据源断开不会阻止服务启动。

会话、审批 token 和结构缓存均为单进程内存数据。HTTP 会话授权记录一小时无请求后过期，需要重新初始化。重启后重新初始化并 prepare；多实例部署需要会话粘滞或共享状态，不支持在不同实例之间直接确认 token。

## 验证与升级注意事项

`mvn clean verify` 包含 SQL 校验、四种方言数值映射、事务/并发 token、缓存并发失效、结果截断、JDBC 关系元数据、HTTP 鉴权及独立 stdio 进程测试。事务和元数据集成测试使用内存 H2；协议测试使用内存 H2 和不可连接的数据库地址，不向外部数据库执行 SQL。上线前仍需在目标数据库和驱动环境完成集成验证。

从旧版迁移时，需要设置数据库环境变量；仅当服务端配置了 HTTP 访问密钥时，才需为客户端补充相同密钥。客户端数据源权限列表和 Origin 白名单配置已移除；全部数据源可读取，写入由每个数据源的 allow-writes 和审批密钥控制。原先可直接执行的任意函数、跨 schema SQL、不含 WHERE 的写入和未经批准的 confirm 会被拒绝。数据源列表增加 allowWrites、查询结果增加 columns、结构结果增加键/索引信息，字段长度类型改为 Long，连接信息不再向客户端展示。

已移除当前服务不使用的模型、Redis、PDF、向量库、Excel、Hutool、Fastjson、Lombok 和 MyBatis-Plus 依赖，JSON 使用 Spring Boot 的 Jackson。MyBatis-Plus 兼容补丁同步删除。
