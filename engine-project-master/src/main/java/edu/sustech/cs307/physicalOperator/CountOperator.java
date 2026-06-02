package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;

public class CountOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private long count;
    private boolean done;

    public CountOperator(PhysicalOperator child) {
        this.child = child;
        this.count = 0;
        this.done = false;
    }

    @Override
    public boolean hasNext() {
        return !done;
    }

    @Override
    public void Begin() throws DBException {
        // 启动输入算子。
        child.Begin();
        count = 0;
        // 遍历输入结果；如果 child 是 FilterOperator，这里统计的是过滤后的行。
        while (child.hasNext()) {
            child.Next();
            if (child.Current() != null) {
                // 每读到一行有效 tuple，计数加一。
                count++;
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
        // count 只输出一行，值为累计行数。
        values.add(new Value(count, ValueType.INTEGER));
        return new TempTuple(values);
    }

    @Override
    public void Close() {
        // 关闭输入算子。
        child.Close();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        // 返回 count 结果列的表头信息。
        schema.add(new ColumnMeta("count", "count", ValueType.INTEGER, Value.INT_SIZE, 0));
        return schema;
    }
    @Override
    public String toString() {
        return PlanTreeFormatter.formatUnaryTree("CountOperator()", child);
    }
}
