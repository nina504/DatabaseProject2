package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;

public class InsertOperator implements PhysicalOperator {
    private final String data_file;
    private final List<Value> values;
    private final DBManager dbManager;
    private final int columnSize;
    private int rowCount;
    private boolean outputed;

    public InsertOperator(String data_file, List<String> columnNames, List<Value> values, DBManager dbManager) {
        this.data_file = data_file;
        this.values = values;
        this.dbManager = dbManager;
        this.columnSize = columnNames.size();
        this.rowCount = 0;
        this.outputed = false;
    }

    @Override
    public boolean hasNext() {
        return !this.outputed;
    }

    @Override
    public void Begin() throws DBException {
        try {
            var fileHandle = dbManager.getRecordManager().OpenFile(data_file);
            // Serialize values to ByteBuf
            ByteBuf buffer = Unpooled.buffer();
            for (int i = 0; i < values.size(); i++) {
                buffer.writeBytes(toFixedWidthBytes(values.get(i)));
                if ((columnSize == 1) || ((i + 1) % columnSize == 0 && i != 0)) {
                    RID rid = fileHandle.InsertRecord(buffer);
                    int rowStart = i + 1 - columnSize;
                    Value[] rowValues = values.subList(rowStart, i + 1).toArray(new Value[0]);
                    dbManager.insertIndexEntries(data_file, rid, rowValues);
                    buffer.clear();
                }
            }
            this.rowCount = values.size() / columnSize;
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBException(ExceptionTypes.InvalidSQL("INSERT",
                    "Failed to insert record: " + e.getMessage()));
        }
    }

    @Override
    public void Next() {
    }

    @Override
    public Tuple Current() {
        ArrayList<Value> values = new ArrayList<>();
        values.add(new Value(rowCount, ValueType.INTEGER));
        this.outputed = true;
        return new TempTuple(values);
    }

    @Override
    public void Close() {
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> outputSchema = new ArrayList<>();
        outputSchema.add(new ColumnMeta("insert", "numberOfInsertRows", ValueType.INTEGER, 0, 0));
        return outputSchema;
    }

    public void reset() {
        // nothing to do
    }

    private byte[] toFixedWidthBytes(Value value) {
        if (value.type == ValueType.CHAR) {
            ByteBuffer buffer = ByteBuffer.allocate(Value.CHAR_SIZE);
            byte[] bytes = value.toString().getBytes();
            buffer.put(bytes, 0, Math.min(bytes.length, Value.CHAR_SIZE));
            return buffer.array();
        }
        return value.ToByte();
    }

    @Override
    public String toString() {
        return "InsertOperator(table=" + data_file + ")";
    }

    public Tuple getNextTuple() {
        return null;
    }
}
