package edu.sustech.cs307.tuple;

import java.util.ArrayList;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;

/**
 * JoinTuple 类表示两个元组的连接结果。
 * 它包含两个元组（leftTuple 和 rightTuple）以及连接后的列信息（tabCol）。
 */
public class JoinTuple extends Tuple {
    private final Tuple leftTuple;
    private final Tuple rightTuple;
    private final TabCol[] tupleSchema;

    public JoinTuple(Tuple leftTuple, Tuple rightTuple, TabCol[] tabCol) {
        // 保存 JOIN 左右两侧 tuple。
        this.leftTuple = leftTuple;
        this.rightTuple = rightTuple;
        // 保存连接后的完整列信息。
        this.tupleSchema = tabCol;
    }

    /**
     * 获取指定记录中对应列的值。
     * 首先尝试从左侧元组中获取值，如果左侧值为 null，则从右侧元组中获取值。
     *
     * @param tabCol 要获取值的列
     * @return 返回对应列的值，如果两侧元组均无值，则返回 null
     */
    @Override
    public Value getValue(TabCol tabCol) throws DBException {
        // 先从左侧 tuple 取值。
        Value leftValue = leftTuple.getValue(tabCol);
        if (leftValue != null) {
            return leftValue;
        }
        // 左侧没有该列时，再从右侧 tuple 取值。
        return rightTuple.getValue(tabCol);
    }

    /**
     * 获取当前元组的模式（列信息）。
     *
     * @return 返回一个包含列信息的 TabCol 数组。
     */
    @Override
    public TabCol[] getTupleSchema() {
        // 返回左右表合并后的 schema。
        return tupleSchema;
    }

    @Override
    public Value[] getValues() throws DBException {
        // 通过 meta 顺序和信息获取所有 Value
        // 按合并后的 schema 顺序取出所有值。
        ArrayList<Value> values = new ArrayList<>();
        for (var tabcol : this.tupleSchema) {
            Value value = getValue(tabcol);
            values.add(value);
        }
        return values.toArray(new Value[0]);
    }
}
