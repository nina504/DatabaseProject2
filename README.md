# CS307 Project 2 测试说明

本项目主工程在 `engine-project-master`。以下命令默认在 WSL 中执行。

## 1. 初始化与启动

```bash
cd ~/DatabaseProject2/engine-project-master
mkdir -p CS307-DB
mvn test -q
```

如果需要干净数据库重新演示：

```bash
cd ~/DatabaseProject2/engine-project-master
rm -rf CS307-DB
mkdir -p CS307-DB
```

启动命令行数据库：

```bash
mvn -q -DskipTests package
mvn -q exec:java -Dexec.mainClass=edu.sustech.cs307.DBEntry
```

如果 `exec:java` 不可用，使用备用启动方式：

```bash
mvn -q -DskipTests package
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" edu.sustech.cs307.DBEntry
```

进入后看到 `CS307-DB>` 即可逐行输入 SQL。退出：

```sql
exit
```

## 2. Maven 测试矩阵

```bash
cd ~/DatabaseProject2/engine-project-master
mvn test -q
```

重点测试类：

- Task 1 Storage：`storage.LRUReplacerTest`、`storage.ClockReplacerTest`、`storage.BufferPoolTest`、`storage.DiskManagerTest`
- Task 2 Record/DDL/DML：`record.RecordFileHandleTest`、`system.RecordManagerTest`、`meta.MetaManagerTest`
- Task 2 Value：`value.ValueComparerTest`
- Task 2 Advanced：`system.AggregateOperatorTest`、`system.SubqueryExpressionTest`、`system.AlterTableTest`
- Task 3 Index：`system.IndexDDLTest`
- Task 4 Transaction：`system.TransactionManagerTest`

单独跑某类测试：

```bash
mvn -q -Dtest=system.IndexDDLTest test
mvn -q -Dtest=system.AggregateOperatorTest test
mvn -q -Dtest=system.TransactionManagerTest test
```

## 3. Task 1：Storage Management

已实现功能：

- LRU 页面替换：`Victim`、`Pin`、`Unpin`
- Clock 页面替换：`Victim`、`Pin`、`Unpin`
- BufferPool 基础读写、pin/unpin、flush

测试命令：

```bash
mvn -q -Dtest=storage.LRUReplacerTest,storage.ClockReplacerTest,storage.BufferPoolTest test
```

## 4. Task 2 Basic：SQL Statement Implementation

### 4.1 表管理与基础 DDL

```sql
create table t(id int, name varchar, age int, gpa double);
show tables;
describe t;
drop table t;
```

### 4.2 Insert / Select / Projection / Where

```sql
create table t(id int, name varchar, age int, gpa double);
insert into t(id, name, age, gpa) values
(1, 'a', 18, 3.6),
(2, 'b', 19, 3.65),
(3, 'abb', 18, 3.86),
(4, 'abc', 19, 2.34),
(5, 'ef', 20, 3.25),
(6, 'bbc', 21, 3.20);

select * from t;
select id, name from t where age >= 19;
select * from t where age > 18 and gpa >= 3.2;
select * from t where age = 18 or name = 'bbc';
```

### 4.3 Update / Delete / Count

```sql
update t set name = 'apple' where id = 1;
select * from t where id = 1;

delete from t where id = 6;
select * from t;

select count(*) from t;
select count(*) from t where age >= 19;
```

### 4.4 Explain

```sql
explain select id, name from t where age >= 19;
```

## 5. Task 2 Advanced：Join、聚合、排序、子查询、ALTER

### 5.1 ORDER BY 升序和降序

```sql
select id, name, age from t order by age asc, id desc;
select id, name from t order by id desc;
```

### 5.2 MAX / MIN / GROUP BY

```sql
select max(gpa) from t;
select min(age) from t;
select age, max(gpa) from t group by age order by age desc;
select name, age, max(gpa) from t group by name, age order by name asc, age desc;
```

说明：`GROUP BY` 使用类型安全的组合 key，支持 `int`、`double`、`varchar` 和多列分组。

### 5.3 Nested Loop Join

```sql
create table u(id int, tag varchar);
insert into u(id, tag) values (1, 'x'), (3, 'y'), (5, 'z');

select t.id, t.name, u.tag from t join u on t.id = u.id order by t.id;
```

### 5.4 IN / NOT IN / EXISTS

```sql
select id from t where id in (select id from u) order by id;
select id from t where id not in (select id from u) order by id;
select id from t where exists (select id from u where u.id = t.id) order by id;
select id from t where not exists (select id from u where u.id = t.id) order by id;
```

### 5.5 Partial ALTER TABLE

```sql
alter table t add column score int;
update t set score = 100 where id = 1;
select * from t order by id;

alter table t drop column score;
select * from t order by id;
```

## 6. Task 3 Advanced：In-memory B+ Tree Index

已实现功能：

- `create index idx on t(col)`
- `drop index idx`
- 多索引同时存在
- 基于 metadata 重启后重建内存 B+Tree
- insert/delete/update 动态维护 B+Tree
- `print index idx` 或 `show index idx` 打印每个 node
- optimizer 在可用索引条件下生成 `IndexScanOperator`

测试 SQL：

```sql
create index idx_age on t(age);
explain select * from t where age = 19;
select * from t where age = 19;
select * from t where age >= 19;
print index idx_age;

create index idx_id on t(id);
select * from t where id >= 3;
delete from t where id = 3;
select * from t where id = 3;
print index idx_id;

drop index idx_age;
```

大数据量建议用 JUnit 验证：

```bash
mvn -q -Dtest=system.IndexDDLTest test
```

## 7. Task 4 Advanced：Transaction

已实现功能：

- `begin`
- `commit`
- `rollback`
- `savepoint name`
- `rollback to savepoint name`
- `release savepoint name`

测试 SQL：

```sql
begin;
insert into t(id, name, age, gpa) values (7, 'tx', 22, 3.7);
savepoint s1;
update t set age = 30 where id = 7;
rollback to s1;
select * from t where id = 7;
commit;

begin;
delete from t where id = 7;
rollback;
select * from t where id = 7;
```

JUnit：

```bash
mvn -q -Dtest=system.TransactionManagerTest test
```

## 8. 演示注意事项

- 每条 SQL 一行输入。
- `CS307-DB` 是运行时数据库目录；想重新演示就删除它。
- WSL/PowerShell 里表格边框可能显示乱码，但列名和值有效。
- 出错后命令行不会退出，可以继续输入下一条 SQL。
