# CS307 Database Engine Project Spec

## 1. 文档目的

本文档基于 `engine-project-master` 代码和 `requirement/CS307-spring26-Project2.md` 需求整理，说明当前代码的作用、已有能力、与课程需求的差距，以及后续完成项目应按什么总体架构和小任务推进。

## 2. 项目现状概览

`engine-project-master` 是一个 Java 17 + Maven 的教学型数据库内核框架。它不是一个完整数据库，而是提供了数据库系统的主要骨架，要求学生补齐存储替换策略、DDL/DML、查询算子、索引和事务等模块。

当前代码主要实现了以下基础能力：

- 命令行入口：`DBEntry` 启动数据库，读取用户 SQL，调用逻辑规划器和物理规划器执行，并以表格形式输出结果。
- SQL 解析：使用 JSQLParser 解析 `SELECT`、`INSERT`、`UPDATE`、`CREATE TABLE`、`SHOW`、`EXPLAIN`、`COMMIT` 等语句。
- 元数据管理：`MetaManager` 以 JSON 文件保存表结构，`TableMeta` 和 `ColumnMeta` 描述表、列、索引元信息。
- 存储管理：`DiskManager` 负责文件和页的磁盘读写，`BufferPool` 负责缓冲池缓存、pin/unpin、脏页刷新。
- 记录管理：`RecordManager` 和 `RecordFileHandle` 负责创建记录文件、插入、读取、更新、删除定长记录。
- 查询执行框架：逻辑算子与物理算子已经有基本链路，例如 `LogicalTableScanOperator -> SeqScanOperator`，`LogicalFilterOperator -> FilterOperator`。
- 基础 DML：`INSERT` 和部分 `UPDATE` 已有实现骨架，全表扫描 `SeqScanOperator` 已经能遍历记录页。

但当前代码仍有大量课程要求未完成或实现不完整，尤其是 LRU/Clock 替换、DDL、投影、条件表达式、DELETE、COUNT、事务保存点、索引和高级查询。

## 3. 当前代码作用与模块说明

### 3.1 入口与执行流程

核心文件：

- `src/main/java/edu/sustech/cs307/DBEntry.java`
- `src/main/java/edu/sustech/cs307/optimizer/LogicalPlanner.java`
- `src/main/java/edu/sustech/cs307/optimizer/PhysicalPlanner.java`

执行流程：

1. `DBEntry.main` 初始化 `DiskManager`、`BufferPool`、`RecordManager`、`MetaManager` 和 `DBManager`。
2. 从命令行读取 SQL。
3. `LogicalPlanner.resolveAndPlan` 将 SQL 解析成逻辑算子树，或直接执行 DDL/事务命令。
4. `PhysicalPlanner.generateOperator` 将逻辑算子转换成物理算子。
5. 物理算子按火山模型执行：`Begin()`、`hasNext()`、`Next()`、`Current()`、`Close()`。
6. 执行结束后刷新缓冲池页面。

### 3.2 元数据层

核心文件：

- `MetaManager`
- `TableMeta`
- `ColumnMeta`
- `TabCol`

作用：

- 维护表名、列名、类型、长度、偏移量。
- 将元数据保存到 `CS307-DB/meta/meta_data.json`。
- 为创建表、查询 schema、投影输出 schema、类型检查提供依据。

现状：

- `createTable`、`getTable`、`saveToJson`、`loadFromJson` 基本可用。
- `dropTable` 未完成。
- `TableMeta.addColumn/dropColumn` 只改 `columns` map，未同步 `columns_list`，会影响输出顺序和持久化。
- 索引元数据字段存在，但没有完整 create/drop index 流程。

### 3.3 存储层

核心文件：

- `DiskManager`
- `BufferPool`
- `Page`
- `PagePosition`
- `LRUReplacer`
- `ClockReplacer`

作用：

- `DiskManager` 将页读写到磁盘文件，并维护每个文件已经分配的页数。
- `BufferPool` 管理内存页框，负责从磁盘取页、创建新页、刷新页、删除页。
- `PageReplacer` 决定缓冲池满时淘汰哪个未 pin 的 frame。

现状：

- `BufferPool` 主体已写好，但依赖替换器正确实现。
- `LRUReplacer` 和 `ClockReplacer` 基本为空，是 Task 1 的重点。
- `BufferPool.DeletePage` 里使用 `pages.remove(frame_id)` 会改变 ArrayList 下标，可能破坏 frame id 稳定性，后续实现时应避免依赖删除数组元素。

### 3.4 记录管理层

核心文件：

- `RecordManager`
- `RecordFileHandle`
- `RecordFileHeader`
- `RecordPageHandle`
- `RecordPageHeader`
- `Record`
- `RID`
- `BitMap`

作用：

- 用定长记录文件保存表数据。
- 页 0 保存文件头，数据页保存 page header、bitmap 和记录槽。
- `RID(pageNum, slotNum)` 定位一条记录。

现状：

- 创建文件、打开文件、插入、读取、更新、删除记录已有实现。
- 记录修改通过脏页和 flush 持久化。
- `SeqScanOperator` 依赖 `BitMap` 遍历已占用 slot。
- `SeqScanOperator.Next` 中 `UnpinPageHandle(currentPageNum, false)` 的 page id/offset 语义可能不一致，建议测试时重点检查 pin 计数和页面释放。

### 3.5 数据值与 Tuple

核心文件：

- `Value`
- `ValueComparer`
- `Tuple`
- `TableTuple`
- `ProjectTuple`
- `TempTuple`
- `JoinTuple`

作用：

- `Value` 定义整数、浮点、字符三类值与字节转换。
- `Tuple` 是算子之间传递的数据抽象。
- `TableTuple` 从 `Record` 中按 schema 解码字段。
- `ProjectTuple` 从输入 tuple 中筛选指定列。

现状：

- `ValueType` 目前是 `INTEGER`、`FLOAT`、`CHAR`，与需求里的 `INT`、`VARCHAR`、`DOUBLE` 不完全一致。
- `Tuple.eval_expr` 只支持 `AND` 和 `=`，不支持 `OR`、`>`、`>=`、`<`、`<=`。
- `TableTuple` 对 CHAR 的解码和 `Value.toString()` 存在不一致风险，应统一使用 `Value.FromByte` 或固定编码格式。

### 3.6 逻辑算子与物理算子

核心文件：

- 逻辑算子：`logicalOperator/*`
- 物理算子：`physicalOperator/*`

作用：

- 逻辑算子表示 SQL 的抽象执行计划。
- 物理算子执行具体算法，例如顺序扫描、过滤、投影、插入、更新、连接、索引扫描。

现状：

- `SeqScanOperator` 基本可用。
- `FilterOperator` 可用但受限于 `Tuple.eval_expr`。
- `ProjectOperator` 能创建 `ProjectTuple`，但 `outputSchema()` 仍返回 child schema。
- `LogicalProjectOperator` 只支持 `SELECT *`，不支持任意列。
- `InsertOperator` 支持单行和多行 values 的部分解析。
- `UpdateOperator` 只支持 `SeqScanOperator` 输入，且物理规划只允许一个 update set。
- `NestedLoopJoinOperator`、`IndexScanOperator`、`InMemoryIndexScanOperator` 未实现。
- `Count`、聚合、排序、IN/EXISTS、ALTER TABLE 等都未实现。

### 3.7 DDL 与功能命令

核心文件：

- `CreateTableExecutor`
- `ShowDatabaseExecutor`
- `ExplainExecutor`
- `DBManager.showTables`
- `DBManager.descTable`
- `DBManager.dropTable`

现状：

- `CREATE TABLE` 支持 `char`、`int`、`float`，但需求是至少 `INT`、`VARCHAR`、`DOUBLE`。
- `SHOW DATABASES` 已支持。
- `SHOW TABLES` 未支持。
- `DESCRIBE table` 未支持。
- `DROP TABLE` 未支持。
- `EXPLAIN` 未支持。

### 3.8 事务

核心文件：

- `TransactionManager`
- `TransactionManagerTest`

现状：

- `begin` 已经能创建一个数据库目录快照，但未维护事务状态。
- `commit` 会持久化当前状态，但未清理快照/状态。
- `rollback`、`savepoint`、`rollbackToSavepoint`、`releaseSavepoint` 都未实现。
- `LogicalPlanner` 目前只手写识别 `BEGIN`，没有识别 `SAVEPOINT`、`ROLLBACK`、`ROLLBACK TO SAVEPOINT`；虽然有 `RELEASE_SAVEPOINT_PATTERN`，但没有实际调用。

需求对应：

- 需要通过 `TransactionManagerTest`，包括事务中 savepoint 栈语义、同名 savepoint 遮蔽、release、rollback 和事务外 no-op 行为。

### 3.9 索引

核心文件：

- `Index`
- `InMemoryOrderedIndex`
- `IndexScanOperator`
- `InMemoryIndexScanOperator`
- `TableMeta.indexes`

现状：

- 有索引接口和元数据字段，但没有 SQL 支持、B+ Tree、索引扫描和维护逻辑。
- `PhysicalPlanner.handleTableScan` 检测到索引会直接抛出 `unimplement`。

## 4. 需求差距总结

### 必做基础部分

- Task 1：实现 `LRUReplacer` 和 `ClockReplacer`。
- Task 2.1：完善基础 SQL，尤其是 `VARCHAR/DOUBLE` 类型、持久化、DELETE、实时刷盘。
- Task 2.1.1：实现 `SHOW TABLES`、`DESCRIBE`、`DROP TABLE`、`EXPLAIN`。
- Task 2.1.2：实现任意列投影、WHERE 条件的 `AND/OR` 和范围比较、DELETE。
- Task 2.1.3：理解并能讲清 `SeqScan`，实现带条件 `COUNT`。
- Task 5：保证命令行交互、异常处理和演示脚本完整。

### 高级部分

- Task 2.2：聚合、GROUP BY、ORDER BY、Nested Loop Join、IN/NOT IN/EXISTS、部分 ALTER TABLE、简单优化器。
- Task 3：内存 B+ Tree 索引、create/drop index、索引元数据、索引维护和打印。
- Task 4：事务 rollback/savepoint/release，并通过测试。

## 5. 建议总体架构

建议不要重写项目，而是在当前框架上补齐功能。总体架构保持如下分层：

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

关键设计原则：

- SQL 解析只负责把语句拆成逻辑计划，不直接做数据扫描。
- 查询条件统一由 `Tuple.eval_expr` 或独立 `ExpressionEvaluator` 处理，避免 Filter、Update、Delete、Count 重复写比较逻辑。
- 所有表结构变化必须同时更新 `MetaManager`、磁盘文件和 JSON 元数据。
- 所有数据修改执行后必须经过 `BufferPool.FlushAllPages` 或对应文件 flush，保证演示时重启后数据仍在。
- 事务初期可以采用目录快照方案，先通过课程测试，再考虑更细粒度日志。
- 索引作为高级任务，先把无索引路径稳定跑通，再接入优化器和 IndexScan。

## 6. 小任务拆分与验收标准

### Phase 0：项目基线与可运行性

目标：

- 确认 Maven 项目能编译、基础测试能运行。
- 建立演示数据库目录 `CS307-DB`。

任务：

- 运行 `mvn test`，记录当前失败用例。
- 修复明显编译错误和乱码字符串导致的 Java 语法问题。
- 准备一组包含 30 行以上的演示 SQL。

验收：

- 项目可启动。
- 能执行 `create table`、`insert`、`select *` 的最小链路。

### Phase 1：存储替换策略

目标：

- 完成 Task 1 的 20 分。

任务：

- 实现 `LRUReplacer.Victim()`：
  - 从 LRU 队首选择可淘汰 frame。
  - 空时返回 `-1`。
  - 淘汰后彻底移除 frame 状态。
- 实现 `LRUReplacer.Pin(frameId)`：
  - 新 frame 进入 pinned 集合。
  - 如果 frame 在 LRU 队列中，移除并标记 pinned。
  - 重复 pin 不改变 size。
  - 容量满时报 `REPLACER IS FULL`。
- 实现 `LRUReplacer.Unpin(frameId)`：
  - 从 pinned 转到 LRU 队尾。
  - 未知 frame 或已 unpin frame 报 `UNPIN PAGE NOT FOUND`。
- 实现 `ClockReplacer`：
  - 维护 frame 状态：pinned / evictable、reference bit、clock hand。
  - Victim 时跳过 pinned，给 reference bit 为 1 的页第二次机会。
  - 淘汰后保持 clock 顺序。

验收：

- 通过 `LRUReplacerTest`。
- 通过 `ClockReplacerTest`。
- BufferPool 在小容量下反复插入和扫描不死锁、不丢页。

### Phase 2：元数据与 DDL

目标：

- 完成基础 DDL：show tables、describe、drop table、explain。

任务：

- 完善 `MetaManager.dropTable`：
  - 表存在则从 map 删除并保存 JSON。
  - 表不存在抛 `TABLE_DOES_NOT_EXIST`。
- 修复 `TableMeta.addColumn/dropColumn`：
  - 同步维护 `columns` 和 `columns_list`。
- 实现 `DBManager.showTables`：
  - 从 `MetaManager.getTableNames()` 输出表名。
- 实现 `DBManager.descTable`：
  - 输出列名和类型。
  - 表不存在时报错并不终止程序。
- 实现 `DBManager.dropTable`：
  - 删除元数据。
  - 删除表目录和数据文件。
  - 清理 BufferPool 中该文件相关页。
  - 更新 DiskManager 元数据。
- 扩展 `LogicalPlanner`：
  - 识别 `SHOW TABLES` 并调用 `dbManager.showTables()`。
  - 识别 `DESCRIBE table`，必要时用手写正则补 JSQLParser 不支持的语法。
  - 识别 `DROP TABLE`。
- 实现 `ExplainExecutor`：
  - 对内部 select 生成逻辑计划。
  - 打印逻辑树或物理树。

验收：

- `show tables;` 正确展示所有表。
- `describe t;` 正确展示字段和类型。
- `drop table t;` 后 `show tables` 不再出现该表，select 该表报错但程序继续运行。
- `explain select ...` 能显示计划树。
- `MetaManagerTest` 通过。

### Phase 3：类型系统与 CREATE TABLE

目标：

- 满足需求中的 `INT`、`VARCHAR`、`DOUBLE`。

任务：

- 扩展或映射 `ValueType`：
  - `INT` 映射当前 `INTEGER`。
  - `DOUBLE` 可映射当前 `FLOAT`，但显示类型应是 double。
  - `VARCHAR(n)` 或 `VARCHAR` 映射当前定长 `CHAR`，默认 64 字节。
- 修改 `CreateTableExecutor`：
  - 接受 `int`、`integer`、`varchar`、`varchar(n)`、`double`、`float`。
  - 列名长度限制不要影响合理演示。
- 统一 `Value` 的字符串编码和解码：
  - `ToByte` 写入长度 + 字节内容。
  - `TableTuple` 读取时用相同格式解析。

验收：

- 能执行 `create table t(id int, name varchar, gpa double);`。
- 插入和查询字符串时不出现乱码、空字节或异常。
- 重启后 schema 和数据仍可读取。

### Phase 4：表达式求值与过滤

目标：

- 支持 WHERE 的任意列、AND、OR、等值和范围查询。

任务：

- 将 `Tuple.eval_expr` 重构为完整表达式求值：
  - 支持 `AndExpression`。
  - 支持 `OrExpression`。
  - 支持括号表达式。
  - 支持 `EqualsTo`、`GreaterThan`、`GreaterThanEquals`、`MinorThan`、`MinorThanEquals`。
  - 支持列在左或右，例如 `18 < t.age`。
- 处理列名解析：
  - 单表查询允许省略表名。
  - 多表查询需要 `table.column` 或通过 schema 判断不歧义。
- 错误处理：
  - 列不存在抛 `COLUMN_DOES_NOT_EXIST`。
  - 类型不匹配抛 `WRONG_COMPARISON_TYPE`。

验收：

- `select * from t where age = 19;`
- `select * from t where age > 18;`
- `select * from t where age >= 18 and gpa < 3.8;`
- `select * from t where age = 18 or name = 'abc';`

### Phase 5：投影与输出 schema

目标：

- 支持 `SELECT 任意列`，不是只能 `SELECT *`。

任务：

- 修改 `LogicalProjectOperator.getOutputSchema`：
  - `AllColumns` 展开 child schema。
  - `Column` 转成 `TabCol`。
  - 支持省略表名时根据 child schema 补表名。
  - 暂时不支持表达式投影时应明确报错。
- 修改 `ProjectOperator.outputSchema`：
  - 返回投影后的 `ColumnMeta` 列表。
  - 输出顺序与 select list 一致。
- 保证 `ProjectTuple.getValues()` 只返回投影列。

验收：

- `select id, name from t;`
- `select t.id, t.name from t where t.age > 18;`
- 表格 header 只显示被选择的列。

### Phase 6：DML 完整化：INSERT、UPDATE、DELETE

目标：

- 完成基础数据修改，并保证实时持久化。

任务：

- INSERT：
  - 支持单行和多行 insert。
  - 支持显式列名，必要时按列名重排 values。
  - 类型检查和列数量检查。
  - 插入后刷新对应数据文件。
- UPDATE：
  - 支持多个 set 项，例如 `set name='a', age=20`。
  - 支持完整 where 条件。
  - 更新后刷新。
- DELETE：
  - 新增 `LogicalDeleteOperator` 和 `DeleteOperator`。
  - `LogicalPlanner` 识别 `Delete` 语句。
  - `DeleteOperator` 基于 SeqScan 遍历，匹配 where 后调用 `RecordFileHandle.DeleteRecord`。
  - 返回删除行数。
  - 删除后刷新。

验收：

- `insert into t (...) values (...);`
- `update t set name='apple' where id = 1;`
- `delete from t where age > 20 or gpa < 2.0;`
- 修改后退出再启动，数据状态保持一致。

### Phase 7：COUNT 与顺序扫描说明

目标：

- 完成 Task 2.1.3 的 Count，并准备 SeqScan 讲解。

任务：

- 支持 `select count(*) from t where ...;`
- 可以实现 `CountOperator`：
  - child 可以是 `SeqScan` 或 `Filter`。
  - Begin 时遍历 child 计数。
  - 输出一行一列。
- 在 `LogicalPlanner/PhysicalPlanner` 中识别 count 聚合。
- 准备 SeqScan 设计说明：
  - 如何打开 record file。
  - 如何读取 file header。
  - 如何遍历 page 和 slot。
  - bitmap 如何判断记录存在。
  - RID 如何定位记录。

验收：

- `select count(*) from t;`
- `select count(*) from t where age >= 18 and gpa > 3.0;`
- 能在展示时讲清 SeqScan 执行逻辑。

### Phase 8：事务

目标：

- 完成 Task 4，优先通过 `TransactionManagerTest`。

任务：

- 在 `TransactionManager` 中维护状态：
  - `boolean active`
  - `Path transactionSnapshot`
  - `Deque<Savepoint>` 或 `List<Savepoint>`
- `begin`：
  - 已有事务则抛 `TRANSACTION_ALREADY_ACTIVE`。
  - 持久化当前状态，创建数据库目录快照。
- `commit`：
  - 事务外 no-op。
  - 事务内持久化当前状态，清理事务快照和 savepoint。
- `rollback`：
  - 事务外 no-op。
  - 事务内恢复 begin 快照到数据库目录。
  - 清理 savepoint 和状态。
- `savepoint(name)`：
  - 事务外抛 `TRANSACTION_REQUIRED`。
  - 创建当前目录快照。
  - 允许同名 savepoint，按栈语义遮蔽旧 savepoint。
- `rollbackToSavepoint(name)`：
  - 事务外抛 `TRANSACTION_REQUIRED`。
  - 找最近的同名 savepoint。
  - 恢复该快照。
  - 目标 savepoint 保留，更新其后的 savepoint 状态。
- `releaseSavepoint(name)`：
  - 事务外抛 `TRANSACTION_REQUIRED`。
  - 删除最近同名 savepoint。
- 扩展 `LogicalPlanner.handleManualTransactionCommand`：
  - `ROLLBACK`
  - `SAVEPOINT name`
  - `ROLLBACK TO SAVEPOINT name`
  - `RELEASE SAVEPOINT name`

验收：

- 通过 `TransactionManagerTest`。
- 能解释 begin 快照、commit 对物理/逻辑结构的影响、rollback/savepoint 设计。

### Phase 9：高级查询

目标：

- 争取 Task 2.2 高级分。

建议优先级：

1. Nested Loop Join
2. ORDER BY
3. MIN/MAX
4. GROUP BY
5. IN / NOT IN / EXISTS
6. ALTER TABLE
7. 简单优化器

任务：

- Nested Loop Join：
  - 实现 `NestedLoopJoinOperator` 的 Begin/hasNext/Next/Current/Close。
  - 使用 `JoinTuple` 合并左右 tuple。
  - 由外层 Filter 或 join 内部条件筛选等值连接。
- ORDER BY：
  - 实现 `SortOperator`，将 child 全部物化到内存后排序。
- MIN/MAX：
  - 实现聚合算子，遍历 child 后输出一行。
- GROUP BY：
  - 用 HashMap 按 group key 聚合。
- IN/EXISTS：
  - 可以先支持简单子查询物化集合，再在表达式求值里判断。
- ALTER TABLE：
  - 先支持 add column / drop column 的元数据修改；数据文件迁移可做简化说明。
- 优化器：
  - 无索引时生成 SeqScan。
  - 有索引且 where 命中索引列时生成 IndexScan。

验收：

- 至少能展示 join、order by、min/max 中若干项。
- explain 能体现不同 SQL 生成不同计划。

### Phase 10：内存 B+ Tree 索引

目标：

- 完成 Task 3 高级索引。

任务：

- 设计 B+ Tree：
  - key 为 `Value`。
  - value 为 `RID` 列表，支持重复键。
  - 支持 insert、delete、search、rangeSearch、printTree。
- 实现索引元数据：
  - `create index idx on t(col)` 修改 `TableMeta.indexes` 并保存 JSON。
  - `drop index idx` 从元数据删除。
- 构建索引：
  - 数据库启动或 create index 时扫描表数据，构建内存 B+ Tree。
  - 支持多个表多个索引。
- 维护索引：
  - insert 时同步插入 key-RID。
  - delete 时同步删除 key-RID。
  - update 索引列时先删旧 key，再插新 key。
- 接入查询：
  - `PhysicalPlanner` 检测 where 条件是否能用索引。
  - 命中时生成 `IndexScanOperator`。
- 打印：
  - 提供 `show index` 或 `print index idx` 命令，打印每个 B+ Tree 节点。

验收：

- 能 create/drop index。
- 大数据集下等值查询和范围查询结果正确。
- 可展示 B+ Tree 节点结构。
- 插入和删除后索引仍正确。

### Phase 11：演示与异常处理

目标：

- 满足 Task 5，保证课堂演示稳定。

任务：

- 准备演示 SQL：
  - 创建至少一张 30 行以上的表。
  - 展示 show/describe/explain。
  - 展示 select 投影、where and/or/range。
  - 展示 update/delete/count。
  - 如果做高级功能，展示 join/index/transaction。
- 异常处理：
  - 表不存在、列不存在、类型不匹配、语法错误不能导致程序退出。
  - 错误用 Logger 输出。
- 关闭数据库：
  - 退出时调用 `closeDBManager` 或等价持久化流程。

验收：

- 演示脚本从空数据库开始可完整跑通。
- 任何一个错误 SQL 后仍可继续输入下一条 SQL。
- 重启后数据仍存在。

## 7. 推荐实现顺序

建议按以下顺序推进：

1. 修复编译和运行基线。
2. Task 1：LRU 和 Clock。
3. DDL：show tables、describe、drop table、explain。
4. 表达式求值：AND/OR/range。
5. 投影：select 指定列。
6. DELETE、完善 UPDATE 和 INSERT。
7. COUNT。
8. 事务测试。
9. Join / Order / Aggregation。
10. B+ Tree 索引。
11. 演示脚本和异常处理。

原因：

- 存储替换器是底层稳定性的前提。
- DDL 和表达式求值是多个功能共用能力。
- DELETE、UPDATE、COUNT 都依赖正确的 where 判断。
- 高级索引和优化器应建立在无索引查询路径稳定之后。

## 8. 最小可交付版本定义

如果时间有限，至少完成以下内容作为基础分目标：

- `LRUReplacerTest` 和 `ClockReplacerTest` 通过。
- `CREATE TABLE` 支持 `INT/VARCHAR/DOUBLE`。
- `INSERT` 支持至少 30 行数据并持久化。
- `SELECT *` 和 `SELECT col1, col2` 正确。
- `WHERE` 支持 `AND/OR` 和 `=/>/>=/</<=`。
- `UPDATE` 带条件可用。
- `DELETE` 带条件可用。
- `COUNT(*)` 带条件可用。
- `SHOW TABLES`、`DESCRIBE`、`DROP TABLE`、`EXPLAIN` 可用。
- 程序遇到错误 SQL 不退出。
- 准备完整演示脚本。

## 9. 风险点

- 当前代码中部分中文注释或输出存在编码乱码，可能影响日志美观，但不一定影响逻辑。
- `Value` 对字符串的序列化/反序列化不统一，容易导致查询输出异常。
- `ProjectOperator.outputSchema` 未修正会导致表头和实际数据不一致。
- `Tuple.eval_expr` 是多个功能的基础，若只局部修补，后续 DELETE/COUNT/UPDATE 会反复出错。
- 事务快照恢复后，内存中的 `DiskManager.filePages` 和 `MetaManager.tables` 也需要重新加载或同步，否则物理文件恢复了但内存状态仍旧。
- 索引是高级任务，不建议在基础查询未稳定前优先做。

## 10. 建议新增测试

- DDL：
  - show tables after create/drop。
  - describe 不存在的表。
- 表达式：
  - OR、范围、列在右侧、字符串比较。
- 投影：
  - `select id,name` 输出 schema 和 value 数量一致。
- DML：
  - delete 后 count 变化。
  - update 多列。
  - 重启后数据保持。
- Count：
  - 空表 count。
  - 带 where count。
- 事务：
  - begin 后 insert rollback。
  - 同名 savepoint。
  - release 后 rollback 报错。
- 索引：
  - create index 后等值查询结果与 seq scan 一致。
  - delete/update 后索引同步。

