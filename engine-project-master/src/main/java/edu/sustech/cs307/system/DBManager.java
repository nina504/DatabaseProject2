package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
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
import edu.sustech.cs307.value.ValueComparer;
import org.apache.commons.lang3.StringUtils;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.IntFunction;

public class DBManager {
    private final MetaManager metaManager;
    /* --- --- --- */
    private final DiskManager diskManager;
    private final BufferPool bufferPool;
    private final RecordManager recordManager;
    private TransactionManager transactionManager;
    private final IntFunction<PageReplacer> replacerFactory;
    private final Map<String, NavigableMap<Value, List<RID>>> indexes;
    private final Comparator<Value> valueComparator;

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
        this.valueComparator = (left, right) -> {
            try {
                return ValueComparer.compare(left, right);
            } catch (DBException e) {
                throw new IllegalArgumentException(e);
            }
        };
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
        NavigableMap<Value, List<RID>> index = indexes.get(indexName);
        if (index == null) {
            return List.of();
        }
        NavigableMap<Value, List<RID>> result;
        switch (operator) {
            case "=" -> result = index.subMap(value, true, value, true);
            case ">" -> result = index.tailMap(value, false);
            case ">=" -> result = index.tailMap(value, true);
            case "<" -> result = index.headMap(value, false);
            case "<=" -> result = index.headMap(value, true);
            default -> result = new TreeMap<>(valueComparator);
        }
        ArrayList<RID> rids = new ArrayList<>();
        for (List<RID> bucket : result.values()) {
            for (RID rid : bucket) {
                rids.add(new RID(rid));
            }
        }
        return rids;
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
        NavigableMap<Value, List<RID>> index = new TreeMap<>(valueComparator);
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
                        addIndexEntry(index, value, rid);
                    }
                }
            } finally {
                bufferPool.unpin_page(pageHandle.page.position, false);
            }
        }
        indexes.put(indexName, index);
    }

    private void addIndexEntry(String indexName, Value value, RID rid) {
        addIndexEntry(indexes.computeIfAbsent(indexName, ignored -> new TreeMap<>(valueComparator)), value, rid);
    }

    private void addIndexEntry(NavigableMap<Value, List<RID>> index, Value value, RID rid) {
        index.computeIfAbsent(value, ignored -> new ArrayList<>()).add(new RID(rid));
    }

    private void removeIndexEntry(String indexName, Value value, RID rid) {
        NavigableMap<Value, List<RID>> index = indexes.get(indexName);
        if (index == null) {
            return;
        }
        List<RID> bucket = index.get(value);
        if (bucket == null) {
            return;
        }
        bucket.remove(rid);
        if (bucket.isEmpty()) {
            index.remove(value);
        }
    }

    private int columnIndex(TableMeta tableMeta, String columnName) throws DBException {
        for (int i = 0; i < tableMeta.columns_list.size(); i++) {
            if (tableMeta.columns_list.get(i).name.equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
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
