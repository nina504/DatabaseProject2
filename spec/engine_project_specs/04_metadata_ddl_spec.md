# Metadata And DDL Spec

## 1. 范围

本 spec 覆盖元数据管理和基础 DDL 命令。

核心文件：

- `MetaManager`
- `TableMeta`
- `ColumnMeta`
- `DBManager`
- `CreateTableExecutor`
- `ShowDatabaseExecutor`
- `ExplainExecutor`
- `LogicalPlanner`

## 2. 元数据任务

实现 `MetaManager.dropTable(tableName)`：

- 表不存在时抛 `TABLE_DOES_NOT_EXIST`。
- 表存在时从 `tables` 中删除。
- 删除后立即 `saveToJson()`。

修复 `TableMeta`：

- `addColumn` 同步维护 `columns` 和 `columns_list`。
- `dropColumn` 同步维护 `columns` 和 `columns_list`。
- `columnCount()` 应与 `columns_list` 一致。

## 3. CREATE TABLE 类型支持

需求至少支持：

- `INT`
- `VARCHAR`
- `DOUBLE`

建议映射：

- `INT` / `INTEGER` -> 当前 `ValueType.INTEGER`
- `VARCHAR` / `VARCHAR(n)` / `CHAR` -> 当前 `ValueType.CHAR`
- `DOUBLE` / `FLOAT` -> 当前 `ValueType.FLOAT`

验收 SQL：

```sql
create table t(id int, name varchar, age int, gpa double);
```

## 4. SHOW TABLES

任务：

- `LogicalPlanner` 识别 `show tables;`。
- `DBManager.showTables()` 从 `metaManager.getTableNames()` 获取表名。
- 使用 Logger 输出表格。

验收：

```sql
show tables;
```

输出中包含已经创建的表。

## 5. DESCRIBE

任务：

- 识别 `describe t;` 或 `desc t;`。
- `DBManager.descTable(tableName)` 输出列名和类型。
- 表不存在时报错但程序继续运行。

验收：

```sql
describe t;
```

输出包含 `id int`、`name varchar/char`、`gpa double/float` 等字段信息。

## 6. DROP TABLE

任务：

- 识别 `drop table t;`。
- 删除元数据。
- 删除表目录和数据文件。
- 删除 BufferPool 中该表数据文件对应页。
- 更新 `DiskManager.filePages`。

验收：

- drop 后 `show tables` 不显示该表。
- drop 后 select 该表报错但程序不退出。
- 重启后该表仍不存在。

## 7. EXPLAIN

任务：

- `ExplainExecutor` 获取内部 SQL。
- 对内部 SQL 生成逻辑计划。
- 输出逻辑算子树，或进一步输出物理算子树。

验收：

```sql
explain select t.id, t.name from t where t.age > 18;
```

输出中应包含：

- `ProjectOperator`
- `LogicalFilterOperator`
- `TableScanOperator`

