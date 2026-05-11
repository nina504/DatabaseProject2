# Project Scope Spec

## 1. 目标

`engine-project-master` 是一个 Java 17 + Maven 的教学型数据库内核框架。项目目标不是调用现成 DBMS，而是在已有框架上补齐一个简易数据库系统，覆盖 SQL 解析、逻辑算子、物理算子、存储层、记录管理、元数据、事务和索引。

## 2. 已有能力

当前代码已经提供：

- 命令行入口：`DBEntry` 读取 SQL 并输出结果表。
- SQL 解析：使用 JSQLParser 解析常见 SQL。
- 元数据管理：`MetaManager` 将表结构保存为 JSON。
- 存储管理：`DiskManager` 负责磁盘文件和页读写。
- 缓冲池：`BufferPool` 负责页缓存、pin/unpin、脏页刷新。
- 记录管理：`RecordManager` 与 `RecordFileHandle` 支持定长记录文件。
- 查询执行框架：已有逻辑算子和物理算子的火山模型链路。
- 基础 DML：`INSERT` 和部分 `UPDATE` 已有骨架。
- 顺序扫描：`SeqScanOperator` 能遍历记录页和 slot。

## 3. 必做需求差距

基础分相关缺口：

- `LRUReplacer` 和 `ClockReplacer` 未完成。
- `SHOW TABLES`、`DESCRIBE`、`DROP TABLE`、`EXPLAIN` 未完成。
- `SELECT` 只完整支持 `*`，任意列投影未完成。
- `WHERE` 只支持很窄的 `AND` 和 `=`，缺少 `OR` 和范围比较。
- `DELETE` 未接入逻辑规划器和物理算子。
- `COUNT(*)` 未实现。
- `CREATE TABLE` 的类型支持与需求中的 `INT/VARCHAR/DOUBLE` 不完全一致。
- 错误 SQL 的异常处理需要更稳定，不能影响后续命令输入。

## 4. 高级需求差距

高级分相关缺口：

- Nested Loop Join 算子未实现。
- 聚合函数 `MAX()`、`MIN()` 和 `GROUP BY` 未实现。
- `ORDER BY` 未实现。
- `IN`、`NOT IN`、`EXISTS` 未实现。
- 部分 `ALTER TABLE` 未实现。
- 简单查询优化器未完成。
- B+ Tree 索引、create/drop index、IndexScan 未实现。
- 事务的 `rollback`、`savepoint`、`rollbackToSavepoint`、`releaseSavepoint` 未实现。

## 5. 基础交付目标

最小可交付版本应至少支持：

- LRU 和 Clock 替换测试通过。
- `CREATE TABLE`、`INSERT`、`UPDATE`、`DELETE`、`SELECT`、`COUNT` 可用。
- `SHOW TABLES`、`DESCRIBE`、`DROP TABLE`、`EXPLAIN` 可用。
- `WHERE` 支持 `AND/OR` 和 `=/>/>=/</<=`。
- 表数据和元数据能够持久化。
- 至少准备一张 30 行以上的演示表。
- 错误 SQL 不导致程序退出。

