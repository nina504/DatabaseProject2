-- 清理上一次演示残留对象。本项目不支持 IF EXISTS，所以首次执行这里报错是正常的，也可用于展示失败日志不影响程序继续运行。
drop index idx_demo_age;
drop table demo_classes;
drop table alter_demo;
drop table delete_demo;
drop table ddl_demo;
drop table demo_students;

-- 演示数据：50 条记录，用于稳定性和存储管理测试。
create table demo_students(id int, name varchar, age int, gpa double);
insert into demo_students(id, name, age, gpa) values (1, 'alice', 18, 3.62), (2, 'bruce', 19, 3.18), (3, 'carol', 20, 3.91), (4, 'david', 21, 2.88), (5, 'emily', 18, 3.45), (6, 'frank', 19, 3.74), (7, 'grace', 20, 3.05), (8, 'henry', 21, 3.33), (9, 'irene', 22, 3.81), (10, 'jack', 18, 2.96), (11, 'kelly', 19, 3.56), (12, 'leo', 20, 3.22), (13, 'mona', 21, 3.67), (14, 'nina', 22, 3.12), (15, 'oscar', 18, 3.99), (16, 'paul', 19, 2.75), (17, 'queen', 20, 3.48), (18, 'rachel', 21, 3.27), (19, 'steve', 22, 3.86), (20, 'tina', 18, 3.14), (21, 'ursula', 19, 3.71), (22, 'victor', 20, 2.94), (23, 'wendy', 21, 3.53), (24, 'xavier', 22, 3.38), (25, 'yara', 18, 3.83), (26, 'zack', 19, 3.01), (27, 'amy', 20, 3.64), (28, 'ben', 21, 2.89), (29, 'cindy', 22, 3.76), (30, 'dan', 18, 3.29), (31, 'eva', 19, 3.92), (32, 'felix', 20, 3.11), (33, 'gina', 21, 3.47), (34, 'hugo', 22, 3.58), (35, 'ivy', 18, 2.97), (36, 'jason', 19, 3.69), (37, 'kate', 20, 3.24), (38, 'louis', 21, 3.88), (39, 'mia', 22, 3.06), (40, 'noah', 18, 3.41), (41, 'olivia', 19, 3.79), (42, 'peter', 20, 2.84), (43, 'quinn', 21, 3.57), (44, 'rose', 22, 3.35), (45, 'sam', 18, 3.73), (46, 'tracy', 19, 3.09), (47, 'uma', 20, 3.95), (48, 'vince', 21, 3.16), (49, 'will', 22, 3.68), (50, 'zoe', 18, 3.52);

-- 1.1 基础 DDL：展示 show tables、describe、explain、drop table，以及 describe 失败日志。
show tables;
describe demo_students;
create table ddl_demo(id int, title varchar, score double);
show tables;
explain select id, name from demo_students where age >= 20 and gpa > 3.5;
drop table ddl_demo;
describe ddl_demo;

-- 1.2 投影和 WHERE：指定列、任意列过滤、AND、OR、等值和范围查询。
select id, name, age, gpa from demo_students where (age >= 20 and gpa > 3.5) or name = 'alice';

-- 1.2 DELETE 完整条件：使用临时表，避免破坏 demo_students 主数据。
create table delete_demo(id int, name varchar, age int, gpa double);
insert into delete_demo(id, name, age, gpa) values (1, 'alice', 18, 3.62), (2, 'bruce', 19, 3.18), (3, 'carol', 20, 3.91), (4, 'david', 21, 2.88), (5, 'emily', 22, 3.45);
delete from delete_demo where (age >= 21 and gpa < 3.0) or name = 'alice';
select * from delete_demo;
drop table delete_demo;

-- 1.3 SeqScan：全表顺序扫描并结合条件过滤。
select * from demo_students where age >= 21 and gpa <= 3.5;

-- 1.3 Count：带完整条件的计数查询。
select count(*) from demo_students where (age >= 20 and gpa > 3.5) or name = 'alice';

-- 2. 高级聚合：GROUP BY 结合 count、max、min、avg。
select age, count(*), max(gpa), min(gpa), avg(gpa) from demo_students group by age;

-- 2. 高级 ORDER BY：多列排序。
select id, name, age, gpa from demo_students where age >= 20 order by age asc, gpa desc;

-- 2. 高级 Nested Loop Join：等值连接并结合过滤。
create table demo_classes(age int, label varchar);
insert into demo_classes(age, label) values (18, 'freshman'), (19, 'sophomore'), (20, 'junior'), (21, 'senior'), (22, 'graduate');
select demo_students.name, demo_students.age, demo_classes.label from demo_students join demo_classes on demo_students.age = demo_classes.age where demo_students.gpa > 3.8;

-- 2. 高级子查询：IN、NOT IN 和 EXISTS。
select id, name, age from demo_students where age in (select age from demo_classes where label = 'junior');
select id, name, age from demo_students where age not in (select age from demo_classes where label = 'graduate');
select id, name, age from demo_students where exists (select * from demo_classes where demo_classes.age = demo_students.age);

-- 2. 高级部分 ALTER TABLE：添加列、更新新列、删除列。
create table alter_demo(id int, age int);
insert into alter_demo(id, age) values (1, 18), (2, 20);
alter table alter_demo add name varchar;
update alter_demo set name = 'alice' where id = 1;
select * from alter_demo;
alter table alter_demo drop name;
select * from alter_demo;
drop table alter_demo;

-- 2. 高级优化器：比较创建索引前后的查询计划。
explain select * from demo_students where age = 20;
create index idx_demo_age on demo_students(age);
explain select * from demo_students where age = 20;
select * from demo_students where age = 20;
print index idx_demo_age;

-- 清理 Join 和子查询演示表。
drop table demo_classes;
