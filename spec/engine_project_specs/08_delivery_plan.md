# Delivery Plan

## 1. 推荐实现顺序

1. 修复编译和运行基线。
2. 实现 `LRUReplacer` 和 `ClockReplacer`。
3. 完成元数据和 DDL：show tables、describe、drop table、explain。
4. 完成表达式求值：AND、OR、范围比较。
5. 完成投影：select 指定列。
6. 完成 DELETE，完善 UPDATE 和 INSERT。
7. 实现 COUNT。
8. 实现事务测试要求。
9. 实现 Join、Order、Aggregation。
10. 实现 B+ Tree 索引。
11. 准备演示脚本和异常处理。

## 2. 最小可交付版本

如果时间有限，至少完成：

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

## 3. 演示脚本建议

准备一份 SQL 脚本，覆盖：

```sql
create table t(id int, name varchar, age int, gpa double);
insert into t(id, name, age, gpa) values (...);
show tables;
describe t;
select * from t;
select id, name from t where age > 18;
select * from t where age = 18 or gpa >= 3.5;
update t set name = 'apple' where id = 1;
delete from t where age < 18;
select count(*) from t where gpa > 3.0;
explain select id, name from t where age > 18;
```

如果实现高级功能，追加：

```sql
begin;
savepoint s1;
rollback to savepoint s1;
commit;
create index idx_age on t(age);
select * from t where age = 20;
select * from t order by gpa desc;
select max(gpa) from t;
```

## 4. 风险点

- 字符串序列化和反序列化目前不统一，容易导致输出乱码。
- `ProjectOperator.outputSchema()` 不修会导致表头错误。
- `Tuple.eval_expr` 不完整会连带影响 SELECT、UPDATE、DELETE、COUNT。
- 事务恢复快照后必须同步内存状态。
- 索引属于高级任务，不建议早于基础查询路径实现。
- 当前部分日志和注释存在编码问题，演示前应尽量清理用户可见输出。

## 5. 建议测试

DDL 测试：

- create 后 show tables。
- describe 不存在的表。
- drop 后 select 报错但程序继续。

表达式测试：

- OR。
- 范围比较。
- 列在表达式右侧。
- 字符串比较。

DML 测试：

- delete 后 count 变化。
- update 多列。
- 重启后数据保持。

事务测试：

- begin 后 insert rollback。
- 同名 savepoint。
- release 后 rollback 报错。

索引测试：

- create index 后等值查询结果与 seq scan 一致。
- delete/update 后索引同步。

