package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;

import java.util.ArrayList;
import java.util.List;

public class IndexScanOperator implements PhysicalOperator {
    private final String tableName;
    private final DBManager dbManager;
    private final String indexName;
    private final String operator;
    private final Value value;
    private TableMeta tableMeta;
    private RecordFileHandle fileHandle;
    private List<RID> rids;
    private int cursor;
    private Tuple currentTuple;
    private boolean open;

    public IndexScanOperator(String tableName, DBManager dbManager, String indexName, String operator, Value value)
            throws DBException {
        this.tableName = tableName;
        this.dbManager = dbManager;
        this.indexName = indexName;
        this.operator = operator;
        this.value = value;
        this.tableMeta = dbManager.getMetaManager().getTable(tableName);
        this.rids = List.of();
    }

    @Override
    public boolean hasNext() {
        return open && cursor < rids.size();
    }

    @Override
    public void Begin() throws DBException {
        this.fileHandle = dbManager.getRecordManager().OpenFile(tableName);
        this.rids = dbManager.searchIndex(tableName, indexName, operator, value);
        this.cursor = 0;
        this.currentTuple = null;
        this.open = true;
    }

    @Override
    public void Next() throws DBException {
        if (!hasNext()) {
            currentTuple = null;
            return;
        }
        RID rid = rids.get(cursor++);
        Record record = fileHandle.GetRecord(rid);
        currentTuple = new TableTuple(tableName, tableMeta, record, rid);
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        if (fileHandle != null) {
            try {
                dbManager.getRecordManager().CloseFile(fileHandle);
            } catch (DBException ignored) {
                // Close must not hide an already produced query result.
            }
        }
        fileHandle = null;
        currentTuple = null;
        open = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return tableMeta == null ? new ArrayList<>() : tableMeta.columns_list;
    }
}
