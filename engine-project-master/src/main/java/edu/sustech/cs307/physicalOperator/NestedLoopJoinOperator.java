package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.JoinTuple;
import edu.sustech.cs307.tuple.Tuple;
import net.sf.jsqlparser.expression.Expression;

public class NestedLoopJoinOperator implements PhysicalOperator {

    private PhysicalOperator leftOperator;
    private PhysicalOperator rightOperator;
    private Collection<Expression> expr;
    private final ArrayList<Tuple> leftTuples = new ArrayList<>();
    private final ArrayList<Tuple> rightTuples = new ArrayList<>();
    private int leftCursor;
    private int rightCursor;
    private Tuple current;
    private TabCol[] outputTupleSchema;

    public NestedLoopJoinOperator(PhysicalOperator leftOperator, PhysicalOperator rightOperator,
            Collection<Expression> expr) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
        this.expr = expr;
    }

    @Override
    public boolean hasNext() throws DBException {
        if (current != null) {
            return true;
        }
        while (leftCursor < leftTuples.size()) {
            Tuple leftTuple = leftTuples.get(leftCursor);
            while (rightCursor < rightTuples.size()) {
                Tuple rightTuple = rightTuples.get(rightCursor++);
                JoinTuple joinTuple = new JoinTuple(leftTuple, rightTuple, outputTupleSchema);
                if (matches(joinTuple)) {
                    current = joinTuple;
                    return true;
                }
            }
            leftCursor++;
            rightCursor = 0;
        }
        return false;
    }

    @Override
    public void Begin() throws DBException {
        leftTuples.clear();
        rightTuples.clear();
        leftCursor = 0;
        rightCursor = 0;
        current = null;
        outputTupleSchema = buildTupleSchema();

        leftOperator.Begin();
        while (leftOperator.hasNext()) {
            leftOperator.Next();
            Tuple tuple = leftOperator.Current();
            if (tuple != null) {
                leftTuples.add(tuple);
            }
        }

        rightOperator.Begin();
        while (rightOperator.hasNext()) {
            rightOperator.Next();
            Tuple tuple = rightOperator.Current();
            if (tuple != null) {
                rightTuples.add(tuple);
            }
        }
    }

    @Override
    public void Next() throws DBException {
        if (current == null) {
            hasNext();
        }
    }

    @Override
    public Tuple Current() {
        Tuple result = current;
        current = null;
        return result;
    }

    @Override
    public void Close() {
        leftOperator.Close();
        rightOperator.Close();
        leftTuples.clear();
        rightTuples.clear();
        current = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> result = new ArrayList<>();
        result.addAll(leftOperator.outputSchema());
        result.addAll(rightOperator.outputSchema());
        return result;
    }

    private TabCol[] buildTupleSchema() {
        ArrayList<TabCol> schema = new ArrayList<>();
        for (ColumnMeta columnMeta : leftOperator.outputSchema()) {
            schema.add(new TabCol(columnMeta.tableName, columnMeta.name));
        }
        for (ColumnMeta columnMeta : rightOperator.outputSchema()) {
            schema.add(new TabCol(columnMeta.tableName, columnMeta.name));
        }
        return schema.toArray(new TabCol[0]);
    }

    private boolean matches(Tuple tuple) throws DBException {
        if (expr == null || expr.isEmpty()) {
            return true;
        }
        for (Expression expression : expr) {
            if (!tuple.eval_expr(expression)) {
                return false;
            }
        }
        return true;
    }
}
