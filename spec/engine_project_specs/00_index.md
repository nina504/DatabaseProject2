# Engine Project Specs Index

本目录将原始总 spec 拆成多个小 spec，方便按模块实现、测试和展示。

建议阅读顺序：

1. [01_project_scope.md](01_project_scope.md)：项目目标、现有能力、需求差距。
2. [02_runtime_architecture.md](02_runtime_architecture.md)：整体运行架构、入口、规划器、算子执行模型。
3. [03_storage_record_spec.md](03_storage_record_spec.md)：存储层、缓冲池、页替换、记录管理。
4. [04_metadata_ddl_spec.md](04_metadata_ddl_spec.md)：元数据、CREATE/SHOW/DESCRIBE/DROP/EXPLAIN。
5. [05_query_processing_spec.md](05_query_processing_spec.md)：SELECT、投影、WHERE、UPDATE、DELETE、COUNT。
6. [06_transaction_spec.md](06_transaction_spec.md)：事务、rollback、savepoint、测试验收。
7. [07_advanced_features_spec.md](07_advanced_features_spec.md)：Join、聚合、排序、优化器、索引。
8. [08_delivery_plan.md](08_delivery_plan.md)：推荐实现顺序、最小交付版本、风险和测试。

原始完整文档仍保留在：

- [../engine_project_requirement_spec.md](../engine_project_requirement_spec.md)

