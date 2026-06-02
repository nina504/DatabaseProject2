package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.List;

public class ExpressionProjectOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<SelectItem<?>> selectItems;
    private final ArrayList<ColumnMeta> outputSchema;
    private Tuple currentTuple;

    public ExpressionProjectOperator(PhysicalOperator child, List<SelectItem<?>> selectItems) throws DBException {
        this.child = child;
        this.selectItems = selectItems;
        this.outputSchema = buildOutputSchema();
    }

    @Override
    public boolean hasNext() throws DBException {
        return child.hasNext();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
    }

    @Override
    public void Next() throws DBException {
        child.Next();
        Tuple inputTuple = child.Current();
        if (inputTuple == null) {
            currentTuple = null;
            return;
        }
        ArrayList<Value> values = new ArrayList<>();
        for (SelectItem<?> selectItem : selectItems) {
            Expression expression = selectItem.getExpression();
            if (expression instanceof AllColumns) {
                values.addAll(List.of(inputTuple.getValues()));
            } else {
                values.add(inputTuple.evaluateExpression(expression));
            }
        }
        currentTuple = new TempTuple(values);
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        child.Close();
        currentTuple = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return outputSchema;
    }

    @Override
    public String toString() {
        return PlanTreeFormatter.formatUnaryTree("ExpressionProjectOperator(selectItems=" + selectItems + ")", child);
    }

    private ArrayList<ColumnMeta> buildOutputSchema() throws DBException {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        int offset = 0;
        for (SelectItem<?> selectItem : selectItems) {
            Expression expression = selectItem.getExpression();
            if (expression instanceof AllColumns) {
                for (ColumnMeta columnMeta : child.outputSchema()) {
                    schema.add(columnMeta);
                    offset += columnMeta.len;
                }
                continue;
            }
            ColumnMeta columnMeta = buildColumnMeta(expression, offset);
            schema.add(columnMeta);
            offset += columnMeta.len;
        }
        return schema;
    }

    private ColumnMeta buildColumnMeta(Expression expression, int offset) throws DBException {
        if (expression instanceof Column column) {
            ColumnMeta source = findColumnMeta(column);
            return new ColumnMeta(source.tableName, source.name, source.type, source.len, offset);
        }
        ValueType type = inferExpressionType(expression);
        int len = type == ValueType.INTEGER ? Value.INT_SIZE : Value.FLOAT_SIZE;
        return new ColumnMeta("expr", expression.toString(), type, len, offset);
    }

    private ColumnMeta findColumnMeta(Column column) throws DBException {
        String tableName = column.getTableName();
        String columnName = column.getColumnName();
        for (ColumnMeta columnMeta : child.outputSchema()) {
            boolean tableMatches = tableName == null || tableName.isEmpty()
                    || columnMeta.tableName.equalsIgnoreCase(tableName);
            if (tableMatches && columnMeta.name.equalsIgnoreCase(columnName)) {
                return columnMeta;
            }
        }
        throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
    }

    private ValueType inferExpressionType(Expression expression) {
        String text = expression.toString();
        if (text.contains("/") || text.contains(".")) {
            return ValueType.FLOAT;
        }
        return ValueType.INTEGER;
    }
}
