package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.index.InMemoryBPlusTreeIndex;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.record.BitMap;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.record.RecordPageHandle;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.value.Value;
import org.apache.commons.lang3.StringUtils;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class DBManager {
    private final MetaManager metaManager;
    /* --- --- --- */
    private final DiskManager diskManager;
    private final BufferPool bufferPool;
    private final RecordManager recordManager;
    private TransactionManager transactionManager;
    private final IntFunction<PageReplacer> replacerFactory;
    private final Map<String, InMemoryBPlusTreeIndex> indexes;

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager) {
        this(diskManager, bufferPool, recordManager, metaManager, null, ClockReplacer::new);
    }

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager, TransactionManager transactionManager,
                     IntFunction<PageReplacer> replacerFactory) {
        this.diskManager = diskManager;
        this.bufferPool = bufferPool;
        this.recordManager = recordManager;
        this.metaManager = metaManager;
        this.replacerFactory = replacerFactory;
        this.transactionManager = transactionManager == null ? new TransactionManager(this) : transactionManager;
        this.indexes = new HashMap<>();
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public BufferPool getBufferPool() {
        return bufferPool;
    }

    public RecordManager getRecordManager() {
        return recordManager;
    }

    public DiskManager getDiskManager() {
        return diskManager;
    }

    public MetaManager getMetaManager() {
        return metaManager;
    }

    public boolean isDirExists(String dir) {
        File file = new File(dir);
        return file.exists() && file.isDirectory();
    }

    /**
     * Displays a formatted table listing all available tables in the database.
     * The output is presented in a bordered ASCII table format with centered table
     * names.
     * Each table name is displayed in a separate row within the ASCII borders.
     */
    public void showTables() {
        Logger.info("|-----------|");
        Logger.info("|  Tables   |");
        Logger.info("|-----------|");
        for (String tableName : metaManager.getTableNames()) {
            Logger.info("|{}|", StringUtils.center(tableName, 11, ' '));
        }
        Logger.info("|-----------|");
    }

    public void descTable(String table_name) throws DBException {
        TableMeta tableMeta = metaManager.getTable(table_name);
        Logger.info("|-------------------------------|");
        Logger.info("|{}|{}|", StringUtils.center("Field", 15, ' '), StringUtils.center("Type", 15, ' '));
        Logger.info("|-------------------------------|");
        for (ColumnMeta column : tableMeta.columns_list) {
            Logger.info("|{}|{}|", StringUtils.center(column.name, 15, ' '), StringUtils.center(column.type.toString(), 15, ' '));
        }
        Logger.info("|-------------------------------|");
    }

    /**
     * Creates a new table in the database with specified name and column metadata.
     * This method sets up both the table metadata and the physical storage
     * structure.
     *
     * @param table_name The name of the table to be created
     * @param columns    List of column metadata defining the table structure
     * @throws DBException If there is an error during table creation
     */
    public void createTable(String table_name, ArrayList<ColumnMeta> columns) throws DBException {
        TableMeta tableMeta = new TableMeta(
                table_name, columns);
        metaManager.createTable(tableMeta);
        String table_folder = String.format("%s/%s", diskManager.getCurrentDir(), table_name);
        File file_folder = new File(table_folder);
        if (!file_folder.exists()) {
            file_folder.mkdirs();
        }
        int record_size = 0;
        for (var col : columns) {
            record_size += col.len;
        }
        String data_file = String.format("%s/%s", table_name, "data");
        recordManager.CreateFile(data_file, record_size);
    }

    /**
     * Drops a table from the database by removing its metadata and associated
     * files.
     *
     * @param table_name The name of the table to be dropped
     * @throws DBException If the table directory does not exist or encounters IO
     *                     errors during deletion
     */
    public void dropTable(String table_name) throws DBException {
        if (!isTableExists(table_name)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(table_name));
        }
        String dataFile = String.format("%s/%s", table_name, "data");
        bufferPool.FlushAllPages("");
        recordManager.DeleteFile(dataFile);
        metaManager.dropTable(table_name);
        File tableDir = new File(String.format("%s/%s", diskManager.getCurrentDir(), table_name));
        if (tableDir.exists()) {
            deleteDirectory(tableDir);
        }
        bufferPool.Reset();
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
        Logger.info("Successfully dropped table: {}", table_name);
    }

    /**
     * Recursively deletes a directory and all its contents.
     * If the given file is a directory, it first deletes all its entries
     * recursively.
     * Finally deletes the file/directory itself.
     *
     * @param file The file or directory to be deleted
     * @throws IOException If deletion of any file or directory fails
     */
    private void deleteDirectory(File file) throws DBException {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteDirectory(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new DBException(ExceptionTypes.BadIOError("File deletion failed: " + file.getAbsolutePath()));
        }
    }

    /**
     * Checks if a table exists in the database.
     *
     * @param table the name of the table to check
     * @return true if the table exists, false otherwise
     */
    public boolean isTableExists(String table) {
        return metaManager.getTableNames().contains(table);
    }

    public void createIndex(String indexName, String tableName, String columnName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (!tableMeta.hasColumn(columnName)) {
            throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
        }
        for (String existingTableName : metaManager.getTableNames()) {
            TableMeta existingTable = metaManager.getTable(existingTableName);
            Map<String, TableMeta.IndexType> indexes = existingTable.getIndexes();
            if (indexes != null && indexes.containsKey(indexName)) {
                throw new DBException(ExceptionTypes.InvalidSQL(
                        "CREATE INDEX", "Index already exists: " + indexName));
            }
        }
        if (tableMeta.getIndexes() == null) {
            tableMeta.setIndexes(new HashMap<>());
        }
        if (tableMeta.getIndexColumns() == null) {
            tableMeta.setIndexColumns(new HashMap<>());
        }
        tableMeta.getIndexes().put(indexName, TableMeta.IndexType.BTREE);
        tableMeta.getIndexColumns().put(indexName, columnName);
        rebuildIndex(tableName, indexName);
        metaManager.saveToJson();
        Logger.info("Successfully created index: {} on {}({})", indexName, tableName, columnName);
    }

    public void dropIndex(String indexName) throws DBException {
        for (String tableName : metaManager.getTableNames()) {
            TableMeta tableMeta = metaManager.getTable(tableName);
            Map<String, TableMeta.IndexType> indexes = tableMeta.getIndexes();
            if (indexes != null && indexes.remove(indexName) != null) {
                if (tableMeta.getIndexColumns() != null) {
                    tableMeta.getIndexColumns().remove(indexName);
                }
                this.indexes.remove(indexName);
                metaManager.saveToJson();
                Logger.info("Successfully dropped index: {}", indexName);
                return;
            }
        }
        throw new DBException(ExceptionTypes.InvalidSQL("DROP INDEX", "Index does not exist: " + indexName));
    }

    public void addColumn(String tableName, String columnName, String typeName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (tableMeta.hasColumn(columnName)) {
            throw new DBException(ExceptionTypes.ColumnAlreadyExist(columnName));
        }
        // 在原有定长记录布局后追加新列，构造新的表结构。
        ColumnMeta newColumn = createColumnMeta(tableName, columnName, typeName, recordSize(tableMeta.columns_list));
        ArrayList<ColumnMeta> newColumns = cloneColumns(tableMeta.columns_list);
        newColumns.add(newColumn);

        // 旧记录需要补上新增字段；新增字段使用该类型的简单默认值。
        List<Value[]> rows = readRows(tableName, tableMeta);
        List<Value[]> rewrittenRows = new ArrayList<>();
        Value defaultValue = defaultValue(newColumn.type);
        for (Value[] row : rows) {
            Value[] rewrittenRow = new Value[newColumns.size()];
            System.arraycopy(row, 0, rewrittenRow, 0, row.length);
            rewrittenRow[rewrittenRow.length - 1] = defaultValue;
            rewrittenRows.add(rewrittenRow);
        }

        // 按新的记录长度重建表数据文件，再安装并持久化新的元数据。
        replaceTableData(tableName, newColumns, rewrittenRows);
        // 新增列不会让已有索引失效，因此保留索引元数据并重建运行时索引。
        installTableMeta(tableMeta, newColumns, copyIndexes(tableMeta), copyIndexColumns(tableMeta));
        rebuildTableIndexes(tableName, tableMeta);
        persistRuntimeState();
        Logger.info("Successfully added column: {}.{}", tableName, columnName);
    }

    public void dropColumn(String tableName, String columnName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        int dropIndex = columnIndex(tableMeta, columnName);
        if (tableMeta.columns_list.size() <= 1) {
            throw new DBException(ExceptionTypes.TableHasNoColumn(tableName));
        }

        // 构造去掉目标列后的紧凑表结构，并重新计算每一列的偏移。
        ArrayList<ColumnMeta> newColumns = new ArrayList<>();
        int offset = 0;
        for (ColumnMeta column : tableMeta.columns_list) {
            if (column.name.equalsIgnoreCase(columnName)) {
                continue;
            }
            newColumns.add(new ColumnMeta(tableName, column.name, column.type, column.len, offset));
            offset += column.len;
        }

        // 重写每一行：复制除被删除列以外的所有值。
        List<Value[]> rows = readRows(tableName, tableMeta);
        List<Value[]> rewrittenRows = new ArrayList<>();
        for (Value[] row : rows) {
            Value[] rewrittenRow = new Value[newColumns.size()];
            int target = 0;
            for (int source = 0; source < row.length; source++) {
                if (source != dropIndex) {
                    rewrittenRow[target++] = row[source];
                }
            }
            rewrittenRows.add(rewrittenRow);
        }

        Map<String, TableMeta.IndexType> newIndexes = copyIndexes(tableMeta);
        Map<String, String> newIndexColumns = copyIndexColumns(tableMeta);
        ArrayList<String> removedIndexes = new ArrayList<>();
        // 建在被删除列上的索引必须同时从元数据和内存索引缓存中移除。
        for (Map.Entry<String, String> entry : copyIndexColumns(tableMeta).entrySet()) {
            if (entry.getValue().equalsIgnoreCase(columnName)) {
                removedIndexes.add(entry.getKey());
            }
        }
        for (String indexName : removedIndexes) {
            newIndexes.remove(indexName);
            newIndexColumns.remove(indexName);
            this.indexes.remove(indexName);
        }

        // 替换物理数据文件，更新表元数据，重建剩余索引，并持久化所有状态。
        replaceTableData(tableName, newColumns, rewrittenRows);
        installTableMeta(tableMeta, newColumns, newIndexes, newIndexColumns);
        rebuildTableIndexes(tableName, tableMeta);
        persistRuntimeState();
        Logger.info("Successfully dropped column: {}.{}", tableName, columnName);
    }

    public String findIndexName(String tableName, String columnName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (tableMeta.getIndexColumns() == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : tableMeta.getIndexColumns().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(columnName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public List<RID> searchIndex(String tableName, String indexName, String operator, Value value) throws DBException {
        ensureIndexBuilt(tableName, indexName);
        InMemoryBPlusTreeIndex index = indexes.get(indexName);
        if (index == null) {
            return List.of();
        }
        return index.search(operator, value);
    }

    public String printIndex(String indexName) throws DBException {
        for (String tableName : metaManager.getTableNames()) {
            TableMeta tableMeta = metaManager.getTable(tableName);
            if (tableMeta.getIndexes() != null && tableMeta.getIndexes().containsKey(indexName)) {
                ensureIndexBuilt(tableName, indexName);
                String output = indexes.get(indexName).printNodes();
                Logger.info("B+Tree index {} on {}({}):\n{}", indexName, tableName,
                        tableMeta.getIndexColumns().get(indexName), output);
                return output;
            }
        }
        throw new DBException(ExceptionTypes.InvalidSQL("PRINT INDEX", "Index does not exist: " + indexName));
    }

    public void insertIndexEntries(String tableName, RID rid, Value[] values) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (tableMeta.getIndexColumns() == null || tableMeta.getIndexColumns().isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : tableMeta.getIndexColumns().entrySet()) {
            ensureIndexBuilt(tableName, entry.getKey());
            addIndexEntry(entry.getKey(), values[columnIndex(tableMeta, entry.getValue())], rid);
        }
    }

    public void deleteIndexEntries(String tableName, RID rid, Value[] values) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (tableMeta.getIndexColumns() == null || tableMeta.getIndexColumns().isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : tableMeta.getIndexColumns().entrySet()) {
            ensureIndexBuilt(tableName, entry.getKey());
            removeIndexEntry(entry.getKey(), values[columnIndex(tableMeta, entry.getValue())], rid);
        }
    }

    public void updateIndexEntries(String tableName, RID rid, Value[] oldValues, Value[] newValues) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (tableMeta.getIndexColumns() == null || tableMeta.getIndexColumns().isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : tableMeta.getIndexColumns().entrySet()) {
            ensureIndexBuilt(tableName, entry.getKey());
            int index = columnIndex(tableMeta, entry.getValue());
            removeIndexEntry(entry.getKey(), oldValues[index], rid);
            addIndexEntry(entry.getKey(), newValues[index], rid);
        }
    }

    private void ensureIndexBuilt(String tableName, String indexName) throws DBException {
        if (!indexes.containsKey(indexName)) {
            rebuildIndex(tableName, indexName);
        }
    }

    private void rebuildIndex(String tableName, String indexName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        String columnName = tableMeta.getIndexColumns().get(indexName);
        if (columnName == null) {
            throw new DBException(ExceptionTypes.InvalidSQL("INDEX", "Missing indexed column: " + indexName));
        }
        InMemoryBPlusTreeIndex index = new InMemoryBPlusTreeIndex();
        RecordFileHandle fileHandle = recordManager.OpenFile(tableName);
        int totalPages = fileHandle.getFileHeader().getNumberOfPages() - 1;
        int recordsPerPage = fileHandle.getFileHeader().getNumberOfRecordsPrePage();
        for (int pageNum = 0; pageNum < totalPages; pageNum++) {
            RecordPageHandle pageHandle = fileHandle.FetchPageHandle(pageNum);
            try {
                for (int slotNum = 0; slotNum < recordsPerPage; slotNum++) {
                    if (BitMap.isSet(pageHandle.bitmap, slotNum)) {
                        RID rid = new RID(pageNum, slotNum);
                        Record record = fileHandle.GetRecord(rid);
                        TableTuple tuple = new TableTuple(tableName, tableMeta, record, rid);
                        Value value = tuple.getValue(new TabCol(tableName, columnName));
                        index.insert(value, rid);
                    }
                }
            } finally {
                bufferPool.unpin_page(pageHandle.page.position, false);
            }
        }
        indexes.put(indexName, index);
    }

    private void addIndexEntry(String indexName, Value value, RID rid) throws DBException {
        indexes.computeIfAbsent(indexName, ignored -> new InMemoryBPlusTreeIndex()).insert(value, rid);
    }

    private void removeIndexEntry(String indexName, Value value, RID rid) throws DBException {
        InMemoryBPlusTreeIndex index = indexes.get(indexName);
        if (index == null) {
            return;
        }
        index.delete(value, rid);
    }

    private int columnIndex(TableMeta tableMeta, String columnName) throws DBException {
        for (int i = 0; i < tableMeta.columns_list.size(); i++) {
            if (tableMeta.columns_list.get(i).name.equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
    }

    private ColumnMeta createColumnMeta(String tableName, String columnName, String typeName, int offset) throws DBException {
        if (columnName.isEmpty() || columnName.length() > 10) {
            throw new DBException(ExceptionTypes.InvalidSQL("ALTER TABLE",
                    String.format("INVALID COLUMN NAME = %s", columnName)));
        }
        if (typeName.equalsIgnoreCase("char") || typeName.equalsIgnoreCase("varchar")) {
            return new ColumnMeta(tableName, columnName, ValueType.CHAR, Value.CHAR_SIZE, offset);
        }
        if (typeName.equalsIgnoreCase("int") || typeName.equalsIgnoreCase("integer")) {
            return new ColumnMeta(tableName, columnName, ValueType.INTEGER, Value.INT_SIZE, offset);
        }
        if (typeName.equalsIgnoreCase("float") || typeName.equalsIgnoreCase("double")) {
            return new ColumnMeta(tableName, columnName, ValueType.FLOAT, Value.FLOAT_SIZE, offset);
        }
        throw new DBException(ExceptionTypes.UnsupportedCommand(String.format("ALTER TABLE %s", tableName)));
    }

    private ArrayList<ColumnMeta> cloneColumns(ArrayList<ColumnMeta> columns) {
        ArrayList<ColumnMeta> cloned = new ArrayList<>();
        int offset = 0;
        for (ColumnMeta column : columns) {
            // 重新计算偏移，保证复制出的 schema 与重写后的定长记录布局一致。
            cloned.add(new ColumnMeta(column.tableName, column.name, column.type, column.len, offset));
            offset += column.len;
        }
        return cloned;
    }

    private int recordSize(ArrayList<ColumnMeta> columns) {
        int size = 0;
        for (ColumnMeta column : columns) {
            size += column.len;
        }
        return size;
    }

    private Value defaultValue(ValueType type) throws DBException {
        // 新增列要给旧行补值；本项目使用 0 或空字符串作为默认值。
        return switch (type) {
            case INTEGER -> new Value(0L);
            case FLOAT -> new Value(0.0);
            case CHAR -> new Value("");
            default -> throw new DBException(ExceptionTypes.UnsupportedValueType(type));
        };
    }

    private List<Value[]> readRows(String tableName, TableMeta tableMeta) throws DBException {
        ArrayList<Value[]> rows = new ArrayList<>();
        RecordFileHandle fileHandle = recordManager.OpenFile(tableName);
        int totalPages = fileHandle.getFileHeader().getNumberOfPages() - 1;
        int recordsPerPage = fileHandle.getFileHeader().getNumberOfRecordsPrePage();
        for (int pageNum = 0; pageNum < totalPages; pageNum++) {
            RecordPageHandle pageHandle = fileHandle.FetchPageHandle(pageNum);
            try {
                for (int slotNum = 0; slotNum < recordsPerPage; slotNum++) {
                    if (BitMap.isSet(pageHandle.bitmap, slotNum)) {
                        // 只读取 bitmap 标记为已占用的 slot，并按当前表结构解码。
                        RID rid = new RID(pageNum, slotNum);
                        Record record = fileHandle.GetRecord(rid);
                        TableTuple tuple = new TableTuple(tableName, tableMeta, record, rid);
                        rows.add(tuple.getValues());
                    }
                }
            } finally {
                bufferPool.unpin_page(pageHandle.page.position, false);
            }
        }
        recordManager.CloseFile(fileHandle);
        return rows;
    }

    private void replaceTableData(String tableName, ArrayList<ColumnMeta> newColumns, List<Value[]> rows)
            throws DBException {
        String dataFile = String.format("%s/%s", tableName, "data");
        String tempFile = String.format("%s/%s", tableName, "data_alter_tmp");
        // ALTER 会改变记录宽度，旧数据文件无法安全地原地修改。
        bufferPool.Reset();
        diskManager.DeleteFile(tempFile);
        diskManager.filePages.remove(tempFile);

        // 将重写后的所有行写入临时文件，临时文件的记录长度匹配新表结构。
        recordManager.CreateFile(tempFile, recordSize(newColumns));
        RecordFileHandle tempHandle = recordManager.OpenDataFile(tempFile);
        for (Value[] row : rows) {
            tempHandle.InsertRecord(serializeRow(row));
        }
        recordManager.CloseFile(tempHandle);
        int tempPages = diskManager.filePages.getOrDefault(tempFile, 1);

        // 用重写后的临时文件替换旧表数据文件。
        bufferPool.Reset();
        diskManager.DeleteFile(dataFile);
        Path source = Path.of(diskManager.getCurrentDir(), tempFile);
        Path target = Path.of(diskManager.getCurrentDir(), dataFile);
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
        diskManager.filePages.remove(tempFile);
        diskManager.filePages.put(dataFile, tempPages);
        bufferPool.Reset();
    }

    private ByteBuf serializeRow(Value[] row) throws DBException {
        // RecordManager 按表列顺序存储定长字节形式的行。
        ByteBuf buffer = Unpooled.buffer();
        for (Value value : row) {
            buffer.writeBytes(toFixedWidthBytes(value));
        }
        return buffer;
    }

    private byte[] toFixedWidthBytes(Value value) throws DBException {
        if (value.type == ValueType.CHAR) {
            ByteBuffer buffer = ByteBuffer.allocate(Value.CHAR_SIZE);
            byte[] bytes = value.toString().getBytes();
            buffer.put(bytes, 0, Math.min(bytes.length, Value.CHAR_SIZE));
            return buffer.array();
        }
        if (value.type == ValueType.INTEGER || value.type == ValueType.FLOAT) {
            return value.ToByte();
        }
        throw new DBException(ExceptionTypes.UnsupportedValueType(value.type));
    }

    private void installTableMeta(TableMeta tableMeta, ArrayList<ColumnMeta> columns,
                                  Map<String, TableMeta.IndexType> newIndexes,
                                  Map<String, String> newIndexColumns) {
        // 同时刷新有序列列表和按列名查找的映射，供 TableTuple/getColumnMeta 使用。
        tableMeta.columns_list = columns;
        HashMap<String, ColumnMeta> columnsByName = new HashMap<>();
        for (ColumnMeta column : columns) {
            columnsByName.put(column.name, column);
        }
        tableMeta.setColumns(columnsByName);
        tableMeta.setIndexes(new HashMap<>(newIndexes));
        tableMeta.setIndexColumns(new HashMap<>(newIndexColumns));
    }

    private Map<String, TableMeta.IndexType> copyIndexes(TableMeta tableMeta) {
        return tableMeta.getIndexes() == null ? new HashMap<>() : new HashMap<>(tableMeta.getIndexes());
    }

    private Map<String, String> copyIndexColumns(TableMeta tableMeta) {
        return tableMeta.getIndexColumns() == null ? new HashMap<>() : new HashMap<>(tableMeta.getIndexColumns());
    }

    private void rebuildTableIndexes(String tableName, TableMeta tableMeta) throws DBException {
        if (tableMeta.getIndexColumns() == null) {
            return;
        }
        // 重写后的记录会获得新的物理 RID，因此所有保留的索引都必须重建。
        for (String indexName : tableMeta.getIndexColumns().keySet()) {
            this.indexes.remove(indexName);
            rebuildIndex(tableName, indexName);
        }
    }

    /**
     * Closes the database manager and performs cleanup operations.
     * This method flushes all pages in the buffer pool, dumps disk manager
     * metadata,
     * and saves meta manager state to JSON format.
     *
     * @throws DBException if an error occurs during the closing process
     */
    public void closeDBManager() throws DBException {
        this.bufferPool.FlushAllPages(null);
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }

    public void beginTransaction() throws DBException {
        transactionManager.begin();
    }

    public void commitTransaction() throws DBException{
        transactionManager.commit();
    }

    public void persistRuntimeState() throws DBException {
        this.bufferPool.FlushAllPages("");
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }
}
