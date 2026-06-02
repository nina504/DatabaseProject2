package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.OrderByElement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


//ORDER BY 通过 LogicalSortOperator 保存排序字段，PhysicalPlanner 转成 SortOperator，
//执行时 SortOperator.Begin() 先读取 child 的所有 tuple，再用 ValueComparer 按多个 OrderByElement 排序，最后按排序后的顺序逐行输出。
public class SortOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<OrderByElement> orderByElements;
    private final ArrayList<Tuple> tuples = new ArrayList<>();
    private int cursor;
    private Tuple current;

    public SortOperator(PhysicalOperator child, List<OrderByElement> orderByElements) {
        // 保存排序输入和 ORDER BY 规则。
        this.child = child;
        this.orderByElements = orderByElements;
    }

    @Override
    public boolean hasNext() {
        // 判断排序后的结果是否还有下一行。
        return cursor < tuples.size();
    }

    @Override
    public void Begin() throws DBException {
        // 初始化排序状态。
        tuples.clear();
        cursor = 0;
        current = null;
        // 先一次性读取 child 的全部输出，放进tuples
        child.Begin();
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple != null) {
                tuples.add(tuple);
            }
        }
        // 然后再按 ORDER BY 规则一次性排序。
        tuples.sort(buildComparator());
    }

    @Override
    public void Next() {
        // 输出排序后的下一行。
        current = hasNext() ? tuples.get(cursor++) : null;
    }

    @Override
    public Tuple Current() {
        // 返回当前排序结果行。
        return current;
    }

    @Override
    public void Close() {
        // 关闭 child 并清理排序缓存。
        child.Close();
        tuples.clear();
        current = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        // 排序不改变列结构，沿用 child 的输出 schema。
        return child.outputSchema();
    }

    @Override
    public String toString() {
        return PlanTreeFormatter.formatUnaryTree("SortOperator(orderBy=" + orderByElements + ")", child);
    }

    private Comparator<Tuple> buildComparator() {
        // 构造多列排序比较器。
        return (left, right) -> {
            try {
                for (OrderByElement orderBy : orderByElements) {
                    // 依次比较每个 ORDER BY 字段。如有多个字段如order by age asc, gpa desc, 则先处理age再处理gpa
                    //若第一个字段不同按照第一个字段排序 并直接返回，若相同则看下面的字段
                    Value leftValue = evaluateOrderValue(left, orderBy.getExpression());
                    Value rightValue = evaluateOrderValue(right, orderBy.getExpression());
                    int comparison = ValueComparer.compare(leftValue, rightValue);
                    if (comparison != 0) {
                        // ASC 升序保持比较结果，DESC 降序反转比较结果。
                        return orderBy.isAsc() ? comparison : -comparison;
                    }
                }
                // 所有排序字段都相等。
                return 0;
            } catch (DBException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private Value evaluateOrderValue(Tuple tuple, Expression expression) throws DBException {
        // 取出当前 tuple 的排序字段值。
        if (expression instanceof Column column) {
            // 优先按列名从输出 schema 中取值。e.g:age
            Value value = valueFromOutputSchema(tuple, column);
            if (value != null) {
                return value;
            }
        }
        // 再按表达式名称从输出 schema 中取值。e.g:age + 1
        Value value = valueFromOutputSchema(tuple, expression.toString());
        if (value != null) {
            return value;
        }
        // 最后直接计算表达式。
        return tuple.evaluateExpression(expression);
    }

    private Value valueFromOutputSchema(Tuple tuple, Column column) throws DBException {
        // 按表名和列名在 child 输出 schema 中查找对应值。
        String tableName = column.getTableName();
        String columnName = column.getColumnName();
        Value[] values = tuple.getValues();
        ArrayList<ColumnMeta> schema = child.outputSchema();
        for (int i = 0; i < schema.size() && i < values.length; i++) {
            ColumnMeta columnMeta = schema.get(i);
            boolean tableMatches = tableName == null || tableName.isEmpty()
                    || columnMeta.tableName.equalsIgnoreCase(tableName);
            if (tableMatches && columnMeta.name.equalsIgnoreCase(columnName)) {
                return values[i];
            }
        }
        return null;
    }

    private Value valueFromOutputSchema(Tuple tuple, String expressionName) throws DBException {
        // 按表达式显示名称在 child 输出 schema 中查找对应值。
        Value[] values = tuple.getValues();
        ArrayList<ColumnMeta> schema = child.outputSchema();
        for (int i = 0; i < schema.size() && i < values.length; i++) {
            if (schema.get(i).name.equalsIgnoreCase(expressionName)) {
                return values[i];
            }
        }
        return null;
    }
}
