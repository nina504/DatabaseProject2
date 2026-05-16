package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;

import java.util.ArrayList;

public class DeleteOperator implements PhysicalOperator {
    private final SeqScanOperator seqScanOperator;
    private final Expression whereExpr;
    private final DBManager dbManager;
    private final String tableName;
    private int deleteCount;
    private boolean done;

    public DeleteOperator(PhysicalOperator inputOperator, DBManager dbManager, String tableName, Expression whereExpr)
            throws DBException {
        if (!(inputOperator instanceof SeqScanOperator seqScanOperator)) {
            throw new DBException(ExceptionTypes.UnsupportedOperator(inputOperator.getClass().getSimpleName()));
        }
        this.seqScanOperator = seqScanOperator;
        this.whereExpr = whereExpr;
        this.dbManager = dbManager;
        this.tableName = tableName;
        this.deleteCount = 0;
        this.done = false;
    }

    @Override
    public boolean hasNext() {
        return !done;
    }

    @Override
    public void Begin() throws DBException {
        seqScanOperator.Begin();
        RecordFileHandle fileHandle = seqScanOperator.getFileHandle();
        while (seqScanOperator.hasNext()) {
            seqScanOperator.Next();
            TableTuple tuple = (TableTuple) seqScanOperator.Current();
            if (tuple != null && (whereExpr == null || tuple.eval_expr(whereExpr))) {
                dbManager.deleteIndexEntries(tableName, tuple.getRID(), tuple.getValues());
                fileHandle.DeleteRecord(tuple.getRID());
                deleteCount++;
            }
        }
    }

    @Override
    public void Next() {
        done = true;
    }

    @Override
    public Tuple Current() {
        ArrayList<Value> values = new ArrayList<>();
        values.add(new Value(deleteCount, ValueType.INTEGER));
        return new TempTuple(values);
    }

    @Override
    public void Close() {
        seqScanOperator.Close();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta("delete", "numberOfDeletedRows", ValueType.INTEGER, Value.INT_SIZE, 0));
        return schema;
    }
}
