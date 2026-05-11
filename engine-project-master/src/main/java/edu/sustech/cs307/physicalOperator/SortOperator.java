package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import net.sf.jsqlparser.statement.select.OrderByElement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SortOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<OrderByElement> orderByElements;
    private final ArrayList<Tuple> tuples = new ArrayList<>();
    private int cursor;
    private Tuple current;

    public SortOperator(PhysicalOperator child, List<OrderByElement> orderByElements) {
        this.child = child;
        this.orderByElements = orderByElements;
    }

    @Override
    public boolean hasNext() {
        return cursor < tuples.size();
    }

    @Override
    public void Begin() throws DBException {
        tuples.clear();
        cursor = 0;
        current = null;
        child.Begin();
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple != null) {
                tuples.add(tuple);
            }
        }
        tuples.sort(buildComparator());
    }

    @Override
    public void Next() {
        current = hasNext() ? tuples.get(cursor++) : null;
    }

    @Override
    public Tuple Current() {
        return current;
    }

    @Override
    public void Close() {
        child.Close();
        tuples.clear();
        current = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return child.outputSchema();
    }

    private Comparator<Tuple> buildComparator() {
        return (left, right) -> {
            try {
                for (OrderByElement orderBy : orderByElements) {
                    Value leftValue = left.evaluateExpression(orderBy.getExpression());
                    Value rightValue = right.evaluateExpression(orderBy.getExpression());
                    int comparison = ValueComparer.compare(leftValue, rightValue);
                    if (comparison != 0) {
                        return orderBy.isAsc() ? comparison : -comparison;
                    }
                }
                return 0;
            } catch (DBException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
