# Storage And Record Spec

## 1. 范围

本 spec 覆盖 Task 1 存储替换策略，以及底层页、缓冲池和记录文件相关工作。

核心文件：

- `DiskManager`
- `BufferPool`
- `Page`
- `PagePosition`
- `LRUReplacer`
- `ClockReplacer`
- `RecordManager`
- `RecordFileHandle`
- `RecordFileHeader`
- `RecordPageHandle`
- `RecordPageHeader`
- `RID`
- `BitMap`

## 2. 现有作用

- `DiskManager` 负责数据库目录下的文件创建、删除、页读写和文件页数元数据。
- `BufferPool` 负责缓存页、pin/unpin、脏页刷新和淘汰。
- `RecordManager` 创建和打开表数据文件。
- `RecordFileHandle` 提供 insert/get/update/delete record。
- `BitMap` 标记记录页中哪些 slot 已被占用。

## 3. LRUReplacer 任务

实现目标：

- `Victim()` 从最久未使用的可淘汰 frame 中选择 victim。
- 空时返回 `-1`。
- victim 后彻底移除该 frame 的所有状态。
- `Pin(frameId)` 将 frame 标记为 pinned。
- 如果 frame 原本在 LRU 队列中，pin 时从 LRU 队列移除。
- 重复 pin 不改变 size。
- 容量满时抛出包含 `REPLACER IS FULL` 的 RuntimeException。
- `Unpin(frameId)` 将 pinned frame 转为可淘汰并放到 LRU 队尾。
- unpin 未知 frame 或已 unpin frame 时抛出包含 `UNPIN PAGE NOT FOUND` 的 RuntimeException。

验收：

- 通过 `LRUReplacerTest`。

## 4. ClockReplacer 任务

实现目标：

- 维护每个 frame 的状态：是否存在、是否 pinned、是否 evictable、reference bit。
- `Pin(frameId)` 将 frame 固定，不能被 victim。
- `Unpin(frameId)` 将 frame 变为可淘汰，并设置 reference bit。
- `Victim()` 按 clock hand 扫描。
- reference bit 为 1 时清零并给予第二次机会。
- reference bit 为 0 且可淘汰时选为 victim。
- pinned frame 必须跳过。
- victim 后移除 frame 状态，并保持 clock 顺序。

验收：

- 通过 `ClockReplacerTest`。

## 5. BufferPool 注意点

需要重点检查：

- `find_victim_page()` 在 free list 为空时依赖替换器返回 frame id。
- 淘汰脏页前必须 flush。
- `FetchPage` 对已有页增加 pin count。
- `unpin_page` 在 pin count 降到 0 时调用 replacer.Unpin。
- `DeletePage` 不应改变 `pages` 的 frame 下标，否则 pageMap 中的 frame id 会失效。

## 6. 记录管理验收

应能完成：

- 创建表数据文件。
- 插入记录。
- 根据 RID 读取记录。
- 更新记录。
- 删除记录。
- 顺序扫描能遍历所有 bitmap 中为 occupied 的 slot。
- flush 后重启仍能读取数据。

