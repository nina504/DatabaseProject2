# Advanced Features Spec

## 1. 范围

本 spec 覆盖高级分相关功能：

- Nested Loop Join
- 聚合函数
- GROUP BY
- ORDER BY
- IN / NOT IN / EXISTS
- ALTER TABLE
- 查询优化器
- 内存 B+ Tree 索引

## 2. Nested Loop Join

核心文件：

- `NestedLoopJoinOperator`
- `LogicalJoinOperator`
- `JoinTuple`
- `PhysicalPlanner.handleJoin`

任务：

- 实现 `Begin()` 初始化左右输入。
- 外层循环遍历 left tuple。
- 内层循环遍历 right tuple。
- 使用 `JoinTuple` 合并左右 tuple。
- 在 join 内部或外层 `FilterOperator` 应用等值连接条件。
- `outputSchema()` 返回左右 schema 拼接。

验收 SQL：

```sql
select * from t1 join t2 on t1.id = t2.id;
```

## 3. ORDER BY

任务：

- 新增 `SortOperator`。
- 将 child 输出物化到内存 list。
- 按 order by 列和升降序排序。
- 再逐行输出。

验收 SQL：

```sql
select * from t order by age desc;
```

## 4. MIN / MAX / GROUP BY

任务：

- 新增聚合逻辑和物理算子。
- `MIN/MAX` 无 group 时输出一行。
- `GROUP BY` 使用 HashMap 按 group key 聚合。

验收 SQL：

```sql
select max(gpa) from t;
select min(age) from t;
select age, max(gpa) from t group by age;
```

## 5. IN / NOT IN / EXISTS

建议实现方式：

- 先支持简单子查询。
- 将子查询结果物化成集合。
- 在表达式求值中判断 membership 或 existence。

验收 SQL：

```sql
select * from t where id in (select id from u);
select * from t where id not in (select id from u);
select * from t where exists (select * from u where u.id = t.id);
```

## 6. ALTER TABLE

建议先支持：

- `alter table t add column c int`
- `alter table t drop column c`

注意：

- 只修改元数据会让旧数据文件布局不一致。
- 如果时间有限，可以只做演示级支持并清楚说明限制。
- 更完整实现需要数据文件重写。

## 7. 查询优化器

目标：

- 不同 SQL 能生成不同查询计划。

建议规则：

- 无索引时使用 SeqScan。
- WHERE 命中索引列时使用 IndexScan。
- 投影永远尽量放在 Filter 后。
- Join 先使用 Nested Loop Join。

## 8. B+ Tree 索引

任务：

- 识别 `create index idx on t(col)`。
- 识别 `drop index idx`。
- 修改 `TableMeta.indexes` 并保存 JSON。
- 实现内存 B+ Tree。
- 支持多个索引。
- create index 时扫描表并构建树。
- insert/delete/update 时维护索引。
- 能打印每个 B+ Tree 节点。
- `PhysicalPlanner` 能在合适条件下生成 `IndexScanOperator`。

验收 SQL：

```sql
create index idx_age on t(age);
select * from t where age = 20;
select * from t where age >= 18 and age <= 22;
drop index idx_age;
```

