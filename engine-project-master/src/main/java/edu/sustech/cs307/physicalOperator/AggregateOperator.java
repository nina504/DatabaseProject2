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
                    values.add(state.aggregateValues.get(aggregateIndex++));
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
        if (!functionName.equals("min") && !functionName.equals("max")) {
            throw new DBException(ExceptionTypes.NotSupportedOperation(function));
        }
        Expression argument = getSingleFunctionArgument(function);
        if (!(argument instanceof Column column)) {
            throw new DBException(ExceptionTypes.NotSupportedOperation(function));
        }
        ColumnMeta source = findColumnMeta(column);
        return new ColumnMeta(functionName, function.toString(), source.type, source.len, offset);
    }

    private void accumulate(AggregateState state, Tuple tuple) throws DBException {
        int aggregateIndex = 0;
        for (SelectItem<?> selectItem : selectItems) {
            Expression expression = selectItem.getExpression();
            if (!(expression instanceof Function function)) {
                continue;
            }
            String functionName = function.getName().toLowerCase(Locale.ROOT);
            Value candidate = tuple.evaluateExpression(getSingleFunctionArgument(function));
            while (state.aggregateValues.size() <= aggregateIndex) {
                state.aggregateValues.add(null);
            }
            Value current = state.aggregateValues.get(aggregateIndex);
            if (current == null
                    || (functionName.equals("min") && ValueComparer.compare(candidate, current) < 0)
                    || (functionName.equals("max") && ValueComparer.compare(candidate, current) > 0)) {
                state.aggregateValues.set(aggregateIndex, candidate);
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
        return function.getParameters().get(0);
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
        private final ArrayList<Value> aggregateValues;

        private AggregateState(List<Value> groupValues) {
            this.groupValues = groupValues;
            this.aggregateValues = new ArrayList<>();
        }
    }
}
