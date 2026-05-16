package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class AggregateOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<SelectItem<?>> selectItems;
    private final List<Expression> groupByExpressions;
    private final ArrayList<ColumnMeta> outputSchema;
    private final ArrayList<Tuple> resultTuples;
    private int cursor;
    private Tuple currentTuple;

    public AggregateOperator(PhysicalOperator child, List<SelectItem<?>> selectItems,
                             List<Expression> groupByExpressions) throws DBException {
        this.child = child;
        this.selectItems = selectItems;
        this.groupByExpressions = groupByExpressions;
        this.outputSchema = buildOutputSchema();
        this.resultTuples = new ArrayList<>();
        this.cursor = 0;
    }

    @Override
    public boolean hasNext() {
        return cursor < resultTuples.size();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        cursor = 0;
        currentTuple = null;
        resultTuples.clear();

        Map<GroupKey, AggregateState> groups = new LinkedHashMap<>();
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) {
                continue;
            }
            List<Value> groupValues = evaluateGroupValues(tuple);
            GroupKey groupKey = new GroupKey(groupValues);
            AggregateState state = groups.computeIfAbsent(groupKey, ignored -> new AggregateState(groupValues));
            accumulate(state, tuple);
        }

        if (groups.isEmpty() && groupByExpressions.isEmpty()) {
            AggregateState state = new AggregateState(List.of());
            groups.put(new GroupKey(List.of()), state);
        }

        for (AggregateState state : groups.values()) {
            ArrayList<Value> values = new ArrayList<>();
            int groupIndex = 0;
            int aggregateIndex = 0;
            for (SelectItem<?> selectItem : selectItems) {
                Expression expression = selectItem.getExpression();
                if (expression instanceof Column) {
                    values.add(state.groupValues.get(groupIndex++));
                } else if (expression instanceof Function) {
                    values.add(finalizeAggregateValue((Function) expression, state.aggregateValues.get(aggregateIndex++)));
                } else {
                    throw new DBException(ExceptionTypes.NotSupportedOperation(expression));
                }
            }
            resultTuples.add(new TempTuple(values));
        }
    }

    @Override
    public void Next() {
        currentTuple = resultTuples.get(cursor++);
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        child.Close();
        currentTuple = null;
        resultTuples.clear();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return outputSchema;
    }

    private ArrayList<ColumnMeta> buildOutputSchema() throws DBException {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        int offset = 0;
        for (SelectItem<?> selectItem : selectItems) {
            Expression expression = selectItem.getExpression();
            ColumnMeta columnMeta;
            if (expression instanceof Column column) {
                columnMeta = findColumnMeta(column);
            } else if (expression instanceof Function function) {
                columnMeta = buildFunctionColumnMeta(function, offset);
            } else {
                throw new DBException(ExceptionTypes.NotSupportedOperation(expression));
            }
            schema.add(columnMeta);
            offset += columnMeta.len;
        }
        return schema;
    }

    private ColumnMeta buildFunctionColumnMeta(Function function, int offset) throws DBException {
        String functionName = function.getName().toLowerCase(Locale.ROOT);
        if (!functionName.equals("min") && !functionName.equals("max") && !functionName.equals("sum")
                && !functionName.equals("avg") && !functionName.equals("count")) {
            throw new DBException(ExceptionTypes.NotSupportedOperation(function));
        }
        if (functionName.equals("count")) {
            return new ColumnMeta("count", function.toString(), ValueType.INTEGER, Value.INT_SIZE, offset);
        }
        ValueType sourceType = inferExpressionType(getSingleFunctionArgument(function));
        ValueType resultType = functionName.equals("avg") ? ValueType.FLOAT : sourceType;
        int len = resultType == ValueType.INTEGER ? Value.INT_SIZE : Value.FLOAT_SIZE;
        return new ColumnMeta(functionName, function.toString(), resultType, len, offset);
    }

    private void accumulate(AggregateState state, Tuple tuple) throws DBException {
        int aggregateIndex = 0;
        for (SelectItem<?> selectItem : selectItems) {
            Expression expression = selectItem.getExpression();
            if (!(expression instanceof Function function)) {
                continue;
            }
            String functionName = function.getName().toLowerCase(Locale.ROOT);
            while (state.aggregateValues.size() <= aggregateIndex) {
                state.aggregateValues.add(new AggregateValue());
            }
            AggregateValue current = state.aggregateValues.get(aggregateIndex);
            if (functionName.equals("count")) {
                current.count++;
                aggregateIndex++;
                continue;
            }
            Value candidate = tuple.evaluateExpression(getSingleFunctionArgument(function));
            if (functionName.equals("min") || functionName.equals("max")) {
                if (current.value == null
                        || (functionName.equals("min") && ValueComparer.compare(candidate, current.value) < 0)
                        || (functionName.equals("max") && ValueComparer.compare(candidate, current.value) > 0)) {
                    current.value = candidate;
                }
            } else if (functionName.equals("sum") || functionName.equals("avg")) {
                accumulateNumeric(current, candidate);
            }
            aggregateIndex++;
        }
    }

    private List<Value> evaluateGroupValues(Tuple tuple) throws DBException {
        ArrayList<Value> values = new ArrayList<>();
        for (Expression expression : groupByExpressions) {
            values.add(tuple.evaluateExpression(expression));
        }
        return values;
    }

    private Expression getSingleFunctionArgument(Function function) throws DBException {
        if (function.getParameters() == null || function.getParameters().size() != 1) {
            throw new DBException(ExceptionTypes.NotSupportedOperation(function));
        }
        if (function.getParameters().get(0) instanceof AllColumns) {
            throw new DBException(ExceptionTypes.NotSupportedOperation(function));
        }
        return function.getParameters().get(0);
    }

    private void accumulateNumeric(AggregateValue current, Value candidate) throws DBException {
        if (candidate.type != ValueType.INTEGER && candidate.type != ValueType.FLOAT) {
            throw new DBException(ExceptionTypes.UnsupportedValueType(candidate.type));
        }
        if (candidate.type == ValueType.FLOAT) {
            current.floatResult = true;
            current.doubleSum += (Double) candidate.value;
        } else {
            long value = (Long) candidate.value;
            current.longSum += value;
            current.doubleSum += value;
        }
        current.count++;
    }

    private Value finalizeAggregateValue(Function function, AggregateValue aggregateValue) throws DBException {
        String functionName = function.getName().toLowerCase(Locale.ROOT);
        if (functionName.equals("min") || functionName.equals("max")) {
            return aggregateValue.value;
        }
        if (functionName.equals("count")) {
            return new Value(aggregateValue.count);
        }
        if (functionName.equals("sum")) {
            if (aggregateValue.floatResult || inferExpressionType(getSingleFunctionArgument(function)) == ValueType.FLOAT) {
                return new Value(aggregateValue.doubleSum);
            }
            return new Value(aggregateValue.longSum);
        }
        if (functionName.equals("avg")) {
            double average = aggregateValue.count == 0 ? 0.0 : aggregateValue.doubleSum / aggregateValue.count;
            return new Value(average);
        }
        throw new DBException(ExceptionTypes.NotSupportedOperation(function));
    }

    private ValueType inferExpressionType(Expression expression) throws DBException {
        if (expression instanceof Column column) {
            return findColumnMeta(column).type;
        }
        String text = expression.toString();
        if (text.contains("/") || text.contains(".")) {
            return ValueType.FLOAT;
        }
        return ValueType.INTEGER;
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

    private static class GroupKey {
        private final List<Value> values;

        private GroupKey(List<Value> values) {
            this.values = List.copyOf(values);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof GroupKey other) || values.size() != other.values.size()) {
                return false;
            }
            for (int i = 0; i < values.size(); i++) {
                Value left = values.get(i);
                Value right = other.values.get(i);
                if (left.type != right.type || !Objects.equals(left.value, right.value)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = 1;
            for (Value value : values) {
                result = 31 * result + Objects.hash(value.type, value.value);
            }
            return result;
        }
    }

    private static class AggregateState {
        private final List<Value> groupValues;
        private final ArrayList<AggregateValue> aggregateValues;

        private AggregateState(List<Value> groupValues) {
            this.groupValues = groupValues;
            this.aggregateValues = new ArrayList<>();
        }
    }

    private static class AggregateValue {
        private Value value;
        private long count;
        private long longSum;
        private double doubleSum;
        private boolean floatResult;
    }
}
