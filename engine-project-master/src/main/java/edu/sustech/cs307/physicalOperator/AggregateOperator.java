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


//把 child 算子输出的 tuple 全部读完，按 GROUP BY 分组，
//对每组计算 min/max/sum/avg/count，最后把每组结果变成 TempTuple 输出



public class AggregateOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<SelectItem<?>> selectItems;
    private final List<Expression> groupByExpressions; //：GROUP BY 后面的字段，比如 age
    private final ArrayList<ColumnMeta> outputSchema;//聚合结果的表头
    private final ArrayList<Tuple> resultTuples;//聚合完成后缓存的所有结果行
    private int cursor;//当前输出到第几行
    private Tuple currentTuple;//当前结果行

    public AggregateOperator(PhysicalOperator child, List<SelectItem<?>> selectItems,
                             List<Expression> groupByExpressions) throws DBException {
        this.child = child;
        this.selectItems = selectItems;
        this.groupByExpressions = groupByExpressions;
        this.outputSchema = buildOutputSchema();
        this.resultTuples = new ArrayList<>();
        this.cursor = 0;
    }

    //判断还有没有聚合结果可以输出。
    //不是边读边输出，而是在 Begin() 里先把所有结果算好，存进 resultTuples，然后遍历这些结果。
    @Override
    public boolean hasNext() {
        return cursor < resultTuples.size();
    }

    @Override
    public void Begin() throws DBException {
        // 启动聚合输入算子。
        child.Begin();
        cursor = 0;
        currentTuple = null;
        resultTuples.clear();

        // 每个 GroupKey 对应一组聚合状态。（AggregateState：被聚合的字段值+当前聚合的值）
        Map<GroupKey, AggregateState> groups = new LinkedHashMap<>();
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) {
                continue;
            }
            // 根据 GROUP BY 字段的 value 构造分组依据 key。
            List<Value> groupValues = evaluateGroupValues(tuple);
            GroupKey groupKey = new GroupKey(groupValues);
            // 获取（若存在）或创建（若不存在）当前分组的聚合状态。
            AggregateState state = groups.computeIfAbsent(groupKey, ignored -> new AggregateState(groupValues));
            // 把当前 tuple 累加到该分组状态中。
            accumulate(state, tuple);
        }

        if (groups.isEmpty() && groupByExpressions.isEmpty()) {
            // 对于无输入的无 GROUP BY 聚合查询，仍返回一个空分组结果。
            //否则下面不会有任何输出。
            AggregateState state = new AggregateState(List.of());
            groups.put(new GroupKey(List.of()), state);
        }

        // 把每个分组的聚合状态转换成最终结果 tuple。
        for (AggregateState state : groups.values()) {
            ArrayList<Value> values = new ArrayList<>();
            int groupIndex = 0; //第几个分组字段
            int aggregateIndex = 0;//第几个聚合字段
            for (SelectItem<?> selectItem : selectItems) {
                Expression expression = selectItem.getExpression();
                if (expression instanceof Column) {
                    // 普通列输出分组字段值。
                    values.add(state.groupValues.get(groupIndex++));
                } else if (expression instanceof Function) {
                    // 聚合函数输出最终聚合值。
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

    @Override
    public String toString() {
        return PlanTreeFormatter.formatUnaryTree("AggregateOperator(selectItems=" + selectItems
                + ", groupBy=" + groupByExpressions + ")", child);
    }

    private ArrayList<ColumnMeta> buildOutputSchema() throws DBException {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        int offset = 0;
        // 根据 SELECT 列表生成聚合结果表头。
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
        // 只支持 min/max/sum/avg/count。
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
        int aggregateIndex = 0;//第几个聚合字段
        // 遍历 SELECT 中的聚合函数，并更新当前分组状态。
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
                // count：每行加一。
                current.count++;
                aggregateIndex++;
                continue;
            }
            //取聚合函数的参数列，拿到当前tuple的该字段的值作为候选
            Value candidate = tuple.evaluateExpression(getSingleFunctionArgument(function));
            if (functionName.equals("min") || functionName.equals("max")) {
                // min/max：保留当前最小值或最大值。
                if (current.value == null
                        || (functionName.equals("min") && ValueComparer.compare(candidate, current.value) < 0)
                        || (functionName.equals("max") && ValueComparer.compare(candidate, current.value) > 0)) {
                    current.value = candidate;
                }
            } else if (functionName.equals("sum") || functionName.equals("avg")) {
                // sum/avg：累加数值和数量。
                accumulateNumeric(current, candidate);
            }
            aggregateIndex++;
        }
    }

    private List<Value> evaluateGroupValues(Tuple tuple) throws DBException {
        ArrayList<Value> values = new ArrayList<>();
        // 计算当前 tuple 的 GROUP BY 字段值。
        for (Expression expression : groupByExpressions) {
            values.add(tuple.evaluateExpression(expression));
        }
        return values;
    }

    //从聚合函数里取出唯一的参数，并检查这个参数是不是当前支持的形式。
    private Expression getSingleFunctionArgument(Function function) throws DBException {
        if (function.getParameters() == null || function.getParameters().size() != 1) {
            throw new DBException(ExceptionTypes.NotSupportedOperation(function));
        }
        if (function.getParameters().get(0) instanceof AllColumns) {
            throw new DBException(ExceptionTypes.NotSupportedOperation(function));
        }
        return function.getParameters().get(0);
    }

    //给 sum/avg 累加当前行的数值，维护整数和浮点两套 sum，
    //同时记录数量，方便最后计算 sum 或 avg。
    private void accumulateNumeric(AggregateValue current, Value candidate) throws DBException {
        // sum/avg 只支持数值类型。
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
        // 把中间聚合状态转换为最终输出值。
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

    //这一行属于哪个分组
    private static class GroupKey {
        // 保存当前分组的 GROUP BY 字段值，例如 group by age 时就是 [age(的值)]。
        private final List<Value> values;

        private GroupKey(List<Value> values) {
            // 复制一份，避免外部修改影响 Map 中的 key。
            this.values = List.copyOf(values);
        }
        //关于某一个key是否出现过，再确认是不是同一个分组
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
                // 类型和值都相同，才认为属于同一个分组。
                if (left.type != right.type || !Objects.equals(left.value, right.value)) {
                    return false;
                }
            }
            return true;
        }
        //关于某一个key是否出现过，先快速找到可能的位置
        @Override
        public int hashCode() {
            int result = 1;
            for (Value value : values) {
                // 和 equals 保持一致：hash 同时考虑类型和值。
                result = 31 * result + Objects.hash(value.type, value.value);
            }
            return result;
        }
    }

    private static class AggregateState {
        // 当前分组的 GROUP BY 值，用于最终输出普通分组列。
        private final List<Value> groupValues;
        // 当前分组中每个聚合函数对应一个 AggregateValue。
        private final ArrayList<AggregateValue> aggregateValues;

        private AggregateState(List<Value> groupValues) {
            this.groupValues = groupValues;
            this.aggregateValues = new ArrayList<>();
        }
    }

    //某一个聚合函数的中间结果
    private static class AggregateValue {
        // min/max 使用，保存当前最小值或最大值。
        private Value value;
        // count/avg 使用，保存当前累计行数。
        private long count;
        // 整数 sum 使用。
        private long longSum;
        // 浮点 sum 和 avg 使用。
        private double doubleSum;
        // 标记 sum 的结果是否需要按浮点输出。
        private boolean floatResult;
    }
}
