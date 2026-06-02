package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.meta.ColumnMeta;

import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.RecordPageHandle;
import edu.sustech.cs307.record.BitMap;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;

import java.util.ArrayList;

//`Begin` 打开表文件并初始化位置，`hasNext` 用 bitmap 找有效 slot，
// `Next` 根据 RID 读记录并前进，`Current` 返回 `TableTuple`，最后 `Close` 关闭文件。

public class SeqScanOperator implements PhysicalOperator {
    private String tableName;
    private DBManager dbManager;
    private TableMeta tableMeta;
    private RecordFileHandle fileHandle;
    private Record currentRecord;
    private RID currentRid;

    private int currentPageNum;
    private int currentSlotNum;
    private int totalPages;
    private int recordsPerPage;
    private boolean isOpen = false;

    public SeqScanOperator(String tableName, DBManager dbManager) {
        this.tableName = tableName;
        this.dbManager = dbManager;
        try {
            this.tableMeta = dbManager.getMetaManager().getTable(tableName);
        } catch (DBException e) {
            // Handle exception properly, maybe log or rethrow
            e.printStackTrace();
        }
    }

    @Override
    public boolean hasNext() { //寻找下一个有效slot
        if (!isOpen)
            return false;
        try {
            // Check if current page and slot are valid, and if there are more records
            if (currentPageNum < totalPages) {
                while (currentPageNum < totalPages) {
                    RecordPageHandle pageHandle = fileHandle.FetchPageHandle(currentPageNum);
                    while (currentSlotNum < recordsPerPage) {
                        if (BitMap.isSet(pageHandle.bitmap, currentSlotNum)) {
                            return true; // Found next record(bitmap为1的slot才有效)
                        }
                        currentSlotNum++;
                    }
                    currentPageNum++;
                    currentSlotNum = 0; // Reset slot num for new page
                }
            }
        } catch (DBException e) {
            e.printStackTrace(); // Handle exception properly
        }
        return false; // No more records
    }

    @Override
    public void Begin() throws DBException {
        try {
            fileHandle = dbManager.getRecordManager().OpenFile(tableName);//打开表文件
            totalPages = fileHandle.getFileHeader().getNumberOfPages() - 1;
            recordsPerPage = fileHandle.getFileHeader().getNumberOfRecordsPrePage();
            currentPageNum = 0; // Start from first data page
            currentSlotNum = 0; // Start from first slot
            isOpen = true;
        } catch (DBException e) {
            e.printStackTrace(); // Handle exception properly
            isOpen = false;
        }
    }

    @Override
    public void Next() {
        if (!isOpen)
            return;
        try {
            if (hasNext()) { // Advance to the next record
                RID rid = new RID(currentPageNum, currentSlotNum);
                currentRecord = fileHandle.GetRecord(rid);
                currentRid = rid;
                currentSlotNum++;
                if (currentSlotNum >= recordsPerPage) {
                    currentPageNum++;
                    currentSlotNum = 0;
                }
            } else {
                currentRecord = null;
                currentRid = null;
            }
        } catch (DBException e) {
            e.printStackTrace(); // Handle exception properly
            currentRecord = null;
            currentRid = null;
        }
    }

    @Override
    public Tuple Current() {
        if (!isOpen || currentRecord == null) {
            return null;
        }
        return new TableTuple(tableName, tableMeta, currentRecord, currentRid);
    }

    @Override
    public void Close() {
        if (!isOpen)
            return;
        try {
            dbManager.getRecordManager().CloseFile(fileHandle);
        } catch (DBException e) {
            e.printStackTrace(); // Handle exception properly
        }
        fileHandle = null;
        currentRecord = null;
        currentRid = null;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return tableMeta.columns_list;
    }

    public RecordFileHandle getFileHandle() {
        return fileHandle;
    }
    @Override
    public String toString() {
        return "SeqScanOperator(table=" + tableName + ")";
    }
}
