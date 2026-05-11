# Transaction Spec

## 1. 范围

本 spec 覆盖 Task 4 事务功能，目标是优先通过 `TransactionManagerTest`。

核心文件：

- `TransactionManager`
- `LogicalPlanner`
- `TransactionManagerTest`

## 2. 当前现状

已有：

- `begin()` 会调用 `createSnapshot()` 创建数据库目录快照。
- `commit()` 会调用 `dbManager.persistRuntimeState()`。

未完成：

- 事务 active 状态。
- `rollback()`
- `savepoint(String savepointName)`
- `rollbackToSavepoint(String savepointName)`
- `releaseSavepoint(String savepointName)`
- `LogicalPlanner` 对 rollback/savepoint/release 的命令识别。

## 3. 状态设计

建议维护：

- `boolean active`
- `Path transactionSnapshot`
- `List<Savepoint>` 或 `Deque<Savepoint>`

Savepoint 字段：

- `String name`
- `Path snapshotDir`

同名 savepoint 使用栈语义：后创建的同名 savepoint 遮蔽旧 savepoint。

## 4. begin

行为：

- 事务已 active 时抛出包含 `TRANSACTION_ALREADY_ACTIVE` 的 DBException。
- 事务未 active 时持久化当前状态。
- 创建 begin 快照。
- 清空 savepoint 列表。
- 设置 active 为 true。

## 5. commit

行为：

- 事务外 commit 为 no-op，不报错。
- 事务内 commit 持久化当前状态。
- 清理 begin 快照和所有 savepoint 快照。
- 设置 active 为 false。

## 6. rollback

行为：

- 事务外 rollback 为 no-op，不报错。
- 事务内恢复 begin 快照到数据库目录。
- 清理所有 savepoint。
- 设置 active 为 false。
- 恢复后需要同步或重建内存状态。

## 7. savepoint

行为：

- 事务外调用抛出包含 `TRANSACTION_REQUIRED` 的 DBException。
- 事务内创建当前数据库目录快照。
- 将 savepoint 追加到列表尾部。
- 允许同名。

## 8. rollbackToSavepoint

行为：

- 事务外调用抛出包含 `TRANSACTION_REQUIRED` 的 DBException。
- 找最近的同名 savepoint。
- 找不到则抛出包含 `SAVEPOINT_DOES_NOT_EXIST` 的 DBException。
- 恢复该 savepoint 快照。
- 目标 savepoint 保留。
- 目标之后的 savepoint 应删除或失效。

## 9. releaseSavepoint

行为：

- 事务外调用抛出包含 `TRANSACTION_REQUIRED` 的 DBException。
- 找最近的同名 savepoint。
- 找不到则抛出包含 `SAVEPOINT_DOES_NOT_EXIST` 的 DBException。
- 删除该 savepoint 快照和列表项。

## 10. SQL 命令识别

`LogicalPlanner.handleManualTransactionCommand` 应支持：

```sql
BEGIN;
START TRANSACTION;
COMMIT;
ROLLBACK;
SAVEPOINT name;
ROLLBACK TO SAVEPOINT name;
RELEASE SAVEPOINT name;
```

## 11. 验收

必须通过：

- `TransactionManagerTest.testRollbackToSavepointThenCommit`
- `testRollbackRestoresStateBeforeBegin`
- `testReleaseSavepointRemovesRollbackTarget`
- `testSavepointRequiresTransaction`
- `testBeginInsideTransactionShouldFail`
- `testRollbackToSavepointRequiresTransaction`
- `testReleaseSavepointRequiresTransaction`
- `testRollbackToSavepointKeepsTargetActive`
- `testDuplicateSavepointNamesFollowStackSemantics`
- `testCommitAndRollbackOutsideTransactionAreNoOps`

