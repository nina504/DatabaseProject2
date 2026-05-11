# Runtime Architecture Spec

## 1. 总体架构

建议继续使用当前代码框架，不重写项目。目标架构如下：

```text
CLI / DBEntry
  |
  v
SQL Parser / LogicalPlanner
  |
  v
Logical Operators
  |
  v
PhysicalPlanner
  |
  v
Physical Operators
  |
  v
Tuple / Value / Expression Evaluation
  |
  v
RecordManager / BufferPool / DiskManager
  |
  v
Disk Files + JSON Metadata
```

## 2. 执行流程

核心文件：

- `src/main/java/edu/sustech/cs307/DBEntry.java`
- `src/main/java/edu/sustech/cs307/optimizer/LogicalPlanner.java`
- `src/main/java/edu/sustech/cs307/optimizer/PhysicalPlanner.java`

流程：

1. `DBEntry.main` 初始化 `DiskManager`、`BufferPool`、`RecordManager`、`MetaManager` 和 `DBManager`。
2. 从命令行读取 SQL。
3. `LogicalPlanner.resolveAndPlan` 解析 SQL。
4. DDL 或事务命令可以直接执行并返回 `null`。
5. 查询和 DML 生成逻辑算子树。
6. `PhysicalPlanner.generateOperator` 生成物理算子树。
7. 物理算子按照 `Begin()`、`hasNext()`、`Next()`、`Current()`、`Close()` 执行。
8. 执行结束后刷新缓冲池页面。

## 3. 设计原则

- SQL 解析层只负责语法识别和逻辑计划构造，不直接扫描数据。
- 物理算子统一使用火山模型接口。
- 条件表达式求值应集中在 `Tuple.eval_expr` 或独立 `ExpressionEvaluator` 中。
- DDL 必须同时更新内存元数据、JSON 元数据和磁盘文件。
- DML 执行后必须保证数据落盘。
- 高级索引和优化器应在无索引路径稳定之后再接入。

## 4. 关键风险

- `LogicalPlanner` 当前有些语句依赖 JSQLParser，有些需要手写正则补齐。
- `ProjectOperator.outputSchema()` 目前返回 child schema，会导致输出表头和数据不一致。
- `Tuple.eval_expr` 是 SELECT、UPDATE、DELETE、COUNT 共用基础，必须优先稳定。
- 事务恢复文件后，还要同步内存中的 `DiskManager.filePages` 和 `MetaManager.tables`。

