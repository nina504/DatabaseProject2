# Project 2：数据库系统开发

**设计方**：教学组  
**Demo 提供者**：ZHANG Ziyang，2025  
**2026 年修改者**：HUANG Liang  
**展示时间**：第 15 周

---

## 项目介绍

为响应教育部“101 计划”中强调数据库系统核心能力培养的要求，并推动数据库教学从“使用数据库”向“构建数据库”转变，本项目聚焦于数据库内核实现。通过模块化实验任务，学生将深入理解数据库管理系统（DBMS）的核心原理与工程实践，培养系统级开发能力。

GitHub 资源：<https://github.com/CS307-Database/engine-project>

本项目基于一个已提供的 Java 框架，其中部分核心模块已经实现。项目要求学生开发一个简单的数据库系统，涉及如下模块：

- SQL 解析
- 逻辑算子
- 物理算子
- 存储层设计
- 数据转换

学生需要理解已有框架，并根据给定内容和代码示例完成或扩展相关模块。当前框架已经包含以下功能：

- 磁盘 I/O 读写（存储层设计）
- 支持 `CREATE TABLE`、`INSERT`、`UPDATE` 语句
- 支持等值过滤（`WHERE`）和逻辑运算（`AND`）
- 支持全表扫描（`SELECT *` 和 `SeqScan` 操作）

学生可以使用提供的项目结构，也可以完全自行构建新的项目结构，只要满足任务要求即可。

---

## 数据准备

在项目展示时，必须至少创建一张测试表，并且该表需要包含 **30 行以上数据**。例如：

### 创建表

```sql
create table t( id int, name char, age int, gpa float);
```

### 插入数据

```sql
insert into t (id, name, age, gpa) values (1, 'a', 18, 3.6);
insert into t (id, name, age, gpa) values (2, 'b', 19, 3.65);
insert into t (id, name, age, gpa) values (3, 'abb', 18, 3.86);
insert into t (id, name, age, gpa) values (4, 'abc', 19, 2.34);
insert into t (id, name, age, gpa) values (5, 'ef', 20, 3.25);
insert into t (id, name, age, gpa) values (6, 'bbc', 21, 3.20);
```

### 查询

```sql
select * from t;
select * from t where t.age = 19;
```

### 更新

```sql
update t set t.name = 'apple' where t.id = 1;
```

---

## 评分说明

- 基础部分共 **80 分**，学生必须实现。
- 高级部分共 **20 分**。
- 高级部分包括：
  - 2.2 Join Operators and Advanced SeqScan Calculations
  - 3. Index
- 高级部分之间的分数可以互相流动，但最多溢出 **5 分**。
- 第 4 部分 Transaction 共 **8 分**，其分数只能溢出到高级部分。
- 如果发现原始代码中的 bug，可以提交 Pull Request，并获得高级部分的分数。

### 展示时的 Q&A

展示过程中的问答形式包括但不限于：

- 回答问题，例如选择题；
- 解释设计过程；
- 现场重写代码。

如果回答错误，则该问答部分及其相关的基础要求和高级要求均不得分。

---

# Task 1：存储管理（20 分）

## 1. Page Replacement Policy：LRU 算法（10 分）

完成 `LRUReplacer` 中的以下方法：

- `Victim()`
- `Pin(int frameId)`
- `Unpin(int frameId)`

并通过 `LRUReplacerTest` 的 JUnit 测试。

## 2. Clock Replacer

实现 `PageReplacer` 接口，并完成 `ClockReplacer` 中的以下方法：

- `Victim()`
- `Pin(int frameId)`
- `Unpin(int frameId)`

并通过 `ClockReplacerTest` 的 JUnit 测试。

---

# Task 2：查询处理（60 分）

## 1. Basic：SQL 语句实现（50 分）

当前教学框架已经支持以下功能，学生可以在此基础上进行扩展。

### 1. 表管理

支持 `CREATE TABLE` 语句，并且至少支持以下数据类型：

- `INT`：整数类型
- `VARCHAR`：可变长度字符串
- `DOUBLE`：双精度浮点数

表必须持久化存储在磁盘上，以保证系统重启后数据仍然保留。

### 2. 数据操作

支持 `INSERT` 和 `UPDATE` 操作。

至少需要插入并维护 30 条记录，用于测试系统稳定性和存储管理能力。

所有数据修改操作，包括：

- `insert`
- `delete`
- `update`

都必须实时写入磁盘，以保证持久性。

---

## 1.1 基础 DDL 操作（20 分）

对于下面所有操作，无论成功还是失败，都不能影响程序执行，并且应当生成适当的日志。

### 支持 `show tables` 命令

期望输出示例：

```sql
show tables;
```

```text
21:29:38.281 INFO: |-----------|
21:29:38.281 INFO: | Tables    |
21:29:38.281 INFO: |-----------|
21:29:38.300 INFO: | t         |
21:29:38.301 INFO: | a         |
21:29:38.301 INFO: |-----------|
```

### 支持 `describe table` 命令

该命令返回：

- 表名
- 列名
- 数据类型

如果表不存在，应当抛出异常或记录错误日志。

示例：

```sql
describe t;
```

```text
21:35:18.211 INFO: |-------------------------|
21:35:18.212 INFO: | Field | Type            |
21:35:18.212 INFO: | id    | int             |
21:35:18.212 INFO: | name  | char            |
21:35:18.212 INFO: | age   | int             |
21:35:18.212 INFO: | gpa   | float           |
21:35:18.212 INFO: |-------------------------|
```

### 支持 `DROP TABLE` 操作

需要正确处理表存在和表不存在两种情况，并生成相应日志。

### 支持 `EXPLAIN` 查询计划展示

示例：

```sql
explain select t.id, t.name from t where t.age>18;
```

输出示例：

```text
21:38:18.602 INFO: ProjectOperator(selectItems=[t.id, t.name])
└── LogicalFilterOperator(condition=t.age > 18)
    └── TableScanOperator(table=t)
```

---

## 1.2 逻辑算子与物理算子（20 分）

### 支持投影操作

需要支持列选择操作，即 `SELECT` 中的投影操作，对应 `ProjectOperator`。

要求可以选择任意列，而不是只能执行 `SELECT *`。

### 实现全表扫描与条件过滤

需要实现 full table scan 和条件过滤，即 `WHERE` 子句解析。需要支持：

- 任意列
- `AND`、`OR` 逻辑
- 等值查询和范围查询：`=`、`>`、`>=`、`<`、`<=`

示例：

```sql
select t.id, t.name from t where t.age > 18;
```

输出示例：

```text
21:39:20.082 INFO: ┌───────────────┐───────────────┐
21:39:20.099 INFO: | t.id          | t.name        |
21:39:20.099 INFO: +───────────────+───────────────+
21:39:20.143 INFO: | 4             | abc           |
21:39:20.143 INFO: +───────────────+───────────────+
21:39:20.143 INFO: | 6             | bbc           |
21:39:20.144 INFO: +───────────────+───────────────+
```

其他查询示例：

```sql
select * from t1 where col1 = xxx or col2 = yyy;
select * from t1 where col1 > xxx;
select * from t1 where col1 > xxx and col2 = yyy;
```

### 支持 `DELETE` 操作（10 分）

需要完整支持带条件的删除操作，包括：

- `AND`
- `OR`
- 等值过滤
- 范围过滤

该部分会在展示时通过 Q&A 进行考察。

---

## 1.3 顺序扫描实现（10 分）

### Basic：SeqScan 顺序扫描实现

该部分会在展示时通过 Q&A 进行考察。

要求：

- 理解框架中已经提供的 `SeqScan`；
- 能够详细解释其实现方式和执行逻辑；
- 展示时会根据实现细节或现场问答进行评分。

### 支持 `Count` 操作（10 分）

需要支持带条件的 `Count` 操作，并完整支持：

- `AND`
- `OR`
- 等值过滤
- 范围过滤

展示时需要解释你对 `count` 操作的设计，该部分会通过 Q&A 进行考察。

---

## 2. Advanced：Join Operators and Advanced SeqScan Calculations（10 分）

需要支持以下高级功能：

- 聚合函数：`MAX()`、`MIN()`、`GROUP BY`
- `ORDER BY`
- 实现用于等值连接的 Nested Loop Join
- 支持 `IN`、`NOT IN`、`EXISTS`
- 支持部分 `ALTER TABLE` 操作
- 实现查询优化器，使其能够为不同 SQL 语句生成不同查询计划

该部分会在展示时通过 Q&A 进行考察。如果该要求未通过，将无法获得 Task 2 高级部分分数。

---

# Task 3：Index，高级部分（10 分）

实现内存中的 B+ Tree 索引。

## 1. 索引支持

需要支持以下功能：

- 识别 `create index xx on t(col)` 语句；
- 识别 `drop index xx` 语句；
- 相应地修改 JSON 文件；
- 在项目框架内，根据索引构建内存中的 B+ Tree；
- 能够打印 B+ Tree 的每个节点；
- 支持创建多个索引，并构建多个 B+ Tree；
- 支持在项目单次运行期间动态创建 B+ Tree，并支持插入和删除操作。

## 2. 验证要求

- 使用足够大的数据集进行测试；
- 确保索引功能的正确性和性能；
- 展示时通过 Q&A 进行考察。

> 注意：该任务重点考察学生对查询优化器核心组件的理解和实现，要求同时具备理论知识和实际工程能力。

---

# Task 4：Transaction，高级部分（共 8 分）

## 1. 通过 `TransactionManagerTest` 的所有测试用例（2 分）

需要在 `TransactionManagerApi` 中实现以下方法：

- `rollback()`
- `savepoint(String savepointName)`
- `rollbackToSavepoint(String savepointName)`
- `releaseSavepoint(String savepointName)`

并完成 `TransactionManagerTest` 中的所有测试用例。

该部分会在展示时通过 Q&A 进行考察。如果该要求未通过，将无法获得 Task 4 的分数。

展示时需要能够解释：

- 如何在 `begin` 命令中设计 snapshot；
- 如果执行 `commit` 命令，物理结构和逻辑结构会发生什么；
- 如何设计 `savepoint` 和 `rollback`。

---

# Task 5：Presentation（10 分）

展示部分要求如下：

1. 具有完整的命令接口，用于命令输入和结果展示；
2. 尽可能完整地设计异常处理机制；
3. 课堂展示表达清晰、有效；
4. 在规定时间内完成展示；
5. 没有组员迟到或导致展示延误。
