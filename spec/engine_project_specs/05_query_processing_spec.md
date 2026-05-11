# Query Processing Spec

## 1. 范围

本 spec 覆盖 SELECT、投影、WHERE 条件、INSERT、UPDATE、DELETE、COUNT 和顺序扫描说明。

核心文件：

- `LogicalPlanner`
- `PhysicalPlanner`
- `Tuple`
- `TableTuple`
- `ProjectTuple`
- `SeqScanOperator`
- `FilterOperator`
- `ProjectOperator`
- `InsertOperator`
- `UpdateOperator`
- 待新增：`LogicalDeleteOperator`
- 待新增：`DeleteOperator`
- 待新增：`CountOperator`

## 2. 表达式求值

目标：

- 所有 WHERE 相关功能共用同一套表达式求值逻辑。

需要支持：

- `AND`
- `OR`
- 括号表达式
- `=`
- `>`
- `>=`
- `<`
- `<=`
- 常量在左或右，例如 `18 < age`
- 单表查询中省略表名的列名

验收 SQL：

```sql
select * from t where age = 19;
select * from t where age > 18;
select * from t where age >= 18 and gpa < 3.8;
select * from t where age = 18 or name = 'abc';
```

## 3. 投影

目标：

- 支持任意列选择，而不是只能 `SELECT *`。

任务：

- `LogicalProjectOperator.getOutputSchema()` 支持 `Column`。
- `AllColumns` 展开为 child schema。
- `ProjectOperator.outputSchema()` 返回投影后的列。
- `ProjectTuple.getValues()` 只返回投影列。

验收 SQL：

```sql
select id, name from t;
select t.id, t.name from t where t.age > 18;
```

输出 header 和每行 value 数量必须一致。

## 4. INSERT

任务：

- 支持单行和多行 values。
- 支持显式列名。
- 根据列名做类型检查和数量检查。
- 插入后刷新数据文件。

验收 SQL：

```sql
insert into t(id, name, age, gpa) values (1, 'a', 18, 3.6);
insert into t(id, name, age, gpa) values (2, 'b', 19, 3.7), (3, 'c', 20, 3.8);
```

## 5. UPDATE

任务：

- 支持完整 WHERE。
- 支持多个 set 项。
- 更新后刷新数据文件。
- 返回更新行数。

验收 SQL：

```sql
update t set name = 'apple' where id = 1;
update t set name = 'x', age = 21 where gpa >= 3.5;
```

## 6. DELETE

任务：

- 新增 `LogicalDeleteOperator`。
- `LogicalPlanner` 识别 JSQLParser 的 `Delete`。
- 新增 `DeleteOperator`。
- Delete 基于 `SeqScanOperator` 遍历记录。
- 匹配 WHERE 后调用 `RecordFileHandle.DeleteRecord(rid)`。
- 返回删除行数。
- 删除后刷新数据文件。

验收 SQL：

```sql
delete from t where age > 20;
delete from t where age = 18 or gpa < 2.0;
```

## 7. COUNT

任务：

- 支持 `select count(*) from t;`
- 支持带 WHERE 的 count。
- 可新增 `CountOperator`，遍历 child 后输出一行一列。

验收 SQL：

```sql
select count(*) from t;
select count(*) from t where age >= 18 and gpa > 3.0;
```

## 8. SeqScan 展示说明

展示时需要能解释：

- `SeqScanOperator.Begin()` 如何打开 record file。
- 如何读取 `RecordFileHeader`。
- 如何从 page 1 开始遍历数据页。
- 如何用 bitmap 判断 slot 是否有记录。
- 如何用 `RID(pageNum, slotNum)` 定位当前记录。
- `Current()` 如何将 `Record` 包装成 `TableTuple`。

