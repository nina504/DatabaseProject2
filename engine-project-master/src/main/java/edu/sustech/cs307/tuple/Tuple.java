package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*AND / OR / NOT
= / > / >= / < / <=
IN / NOT IN
EXISTS / NOT EXISTS
算术表达式 age + 1
列引用 users.age
常量 18, 'alice', 3.5 */

public abstract class Tuple {
    public abstract Value getValue(TabCol tabCol) throws DBException;

    public abstract TabCol[] getTupleSchema();

    public abstract Value[] getValues() throws DBException;

    public boolean eval_expr(Expression expr) throws DBException {
        return eval_expr(expr, new EvalContext(null, null));
    }

    public boolean eval_expr(Expression expr, DBManager dbManager) throws DBException {
        return eval_expr(expr, new EvalContext(dbManager, null));
    }

    public boolean eval_expr(Expression expr, EvalContext context) throws DBException {
        return evaluateCondition(this, expr, context);
    }

    public static class EvalContext {
        private final DBManager dbManager;
        private final Tuple outerTuple;
        private final Map<String, List<Value>> valueSubqueryCache;
        private final Map<String, Boolean> existsSubqueryCache;

        public EvalContext(DBManager dbManager, Tuple outerTuple) {
            this(dbManager, outerTuple, new HashMap<>(), new HashMap<>());
        }

        private EvalContext(DBManager dbManager, Tuple outerTuple, Map<String, List<Value>> valueSubqueryCache,
                            Map<String, Boolean> existsSubqueryCache) {
            this.dbManager = dbManager;
            this.outerTuple = outerTuple;
            this.valueSubqueryCache = valueSubqueryCache;
            this.existsSubqueryCache = existsSubqueryCache;
        }

        public EvalContext withOuterTuple(Tuple outerTuple) {
            return new EvalContext(dbManager, outerTuple, valueSubqueryCache, existsSubqueryCache);
        }
    }

    private boolean evaluateCondition(Tuple tuple, Expression whereExpr, EvalContext context) throws DBException {
        if (whereExpr == null) {
            return true;
        }
        if (whereExpr instanceof AndExpression andExpr) {
            return evaluateCondition(tuple, andExpr.getLeftExpression(), context)
                    && evaluateCondition(tuple, andExpr.getRightExpression(), context);
        } else if (whereExpr instanceof OrExpression orExpr) {
            return evaluateCondition(tuple, orExpr.getLeftExpression(), context)
                    || evaluateCondition(tuple, orExpr.getRightExpression(), context);
        } else if (whereExpr instanceof Parenthesis parenthesis) {
            return evaluateCondition(tuple, parenthesis.getExpression(), context);
        } else if (whereExpr instanceof NotExpression notExpression) {
            return !evaluateCondition(tuple, notExpression.getExpression(), context);
        } else if (whereExpr instanceof InExpression inExpression) {
            return evaluateInExpression(tuple, inExpression, context);
        } else if (whereExpr instanceof ExistsExpression existsExpression) {
            return evaluateExistsExpression(tuple, existsExpression, context);
        } else if (whereExpr instanceof ExpressionList<?> expressionList) {
            if (expressionList.size() == 1) {
                return evaluateCondition(tuple, expressionList.get(0), context);
            }
            throw new DBException(ExceptionTypes.UnsupportedExpression(whereExpr));
        } else if (whereExpr instanceof BinaryExpression binaryExpression) {
            return evaluateBinaryExpression(tuple, binaryExpression, context);
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(whereExpr));
        }
    }

    private boolean evaluateBinaryExpression(Tuple tuple, BinaryExpression binaryExpr, EvalContext context) throws DBException {
        Expression leftExpr = binaryExpr.getLeftExpression();
        Expression rightExpr = binaryExpr.getRightExpression();
        String operator = binaryExpr.getStringExpression();
        Value leftValue = evaluateExpression(leftExpr, tuple, context);
        Value rightValue = evaluateExpression(rightExpr, tuple, context);

        if (leftValue == null || rightValue == null)
            return false;

        int comparisonResult = ValueComparer.compare(leftValue, rightValue);
        if (binaryExpr instanceof EqualsTo || operator.equals("=")) {
            return comparisonResult == 0;
        } else if (binaryExpr instanceof GreaterThan || operator.equals(">")) {
            return comparisonResult > 0;
        } else if (binaryExpr instanceof GreaterThanEquals || operator.equals(">=")) {
            return comparisonResult >= 0;
        } else if (binaryExpr instanceof MinorThan || operator.equals("<")) {
            return comparisonResult < 0;
        } else if (binaryExpr instanceof MinorThanEquals || operator.equals("<=")) {
            return comparisonResult <= 0;
        }
        return false;
    }

    private Value getConstantValue(Expression expr) {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        }
        return null; // Unsupported constant type
    }

    private String findTableNameForColumn(Tuple tuple, String columnName) {
        for (TabCol tabCol : tuple.getTupleSchema()) {
            if (tabCol.getColumnName().equalsIgnoreCase(columnName)) {
                return tabCol.getTableName();
            }
        }
        return null;
    }

    public Value evaluateExpression(Expression expr) throws DBException {
        return evaluateExpression(expr, this, new EvalContext(null, null));
    }

    private Value evaluateExpression(Expression expr, Tuple tuple, EvalContext context) throws DBException {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        } else if (expr instanceof Column) {
            return resolveColumnValue(tuple, (Column) expr, context.outerTuple);
        } else if (expr instanceof Parenthesis parenthesis) {
            return evaluateExpression(parenthesis.getExpression(), tuple, context);
        } else if (expr instanceof ExpressionList<?> expressionList && expressionList.size() == 1) {
            return evaluateExpression(expressionList.get(0), tuple, context);
        } else if (expr instanceof Addition || expr instanceof Subtraction
                || expr instanceof Multiplication || expr instanceof Division) {
            return evaluateArithmeticExpression((BinaryExpression) expr, tuple, context);
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
        }
    }

    private Value evaluateArithmeticExpression(BinaryExpression expr, Tuple tuple, EvalContext context)
            throws DBException {
        Value leftValue = evaluateExpression(expr.getLeftExpression(), tuple, context);
        Value rightValue = evaluateExpression(expr.getRightExpression(), tuple, context);
        if (!isNumeric(leftValue) || !isNumeric(rightValue)) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
        }
        boolean resultIsFloat = leftValue.type == ValueType.FLOAT
                || rightValue.type == ValueType.FLOAT
                || expr instanceof Division;
        double left = numericAsDouble(leftValue);
        double right = numericAsDouble(rightValue);
        double result;
        if (expr instanceof Addition) {
            result = left + right;
        } else if (expr instanceof Subtraction) {
            result = left - right;
        } else if (expr instanceof Multiplication) {
            result = left * right;
        } else {
            result = left / right;
        }
        if (resultIsFloat) {
            return new Value(result);
        }
        return new Value((long) result);
    }

    private boolean isNumeric(Value value) {
        return value != null && (value.type == ValueType.INTEGER || value.type == ValueType.FLOAT);
    }

    private double numericAsDouble(Value value) {
        if (value.type == ValueType.INTEGER) {
            return ((Long) value.value).doubleValue();
        }
        return (Double) value.value;
    }

    //遍历右边，找是否有与左边相等的元素（适合子查询小）
    private boolean evaluateInExpression(Tuple tuple, InExpression inExpression, EvalContext context) throws DBException {
        // IN / NOT IN 的左侧表达式，例如 age IN (...) 中的 age，按当前 tuple 求值。
        Value leftValue = evaluateExpression(inExpression.getLeftExpression(), tuple, context);
        if (leftValue == null) {
            return false;
        }
        // 右侧可以是常量列表，也可以是子查询；统一转换成候选 Value 列表。
        List<Value> candidates = evaluateInCandidates(inExpression.getRightExpression(), tuple, context);
        boolean matched = false;
        // 逐个比较左值是否出现在候选列表中。
        for (Value candidate : candidates) {
            if (ValueComparer.compare(leftValue, candidate) == 0) {
                matched = true;
                break;
            }
        }
        // NOT IN 在 IN 的匹配结果上取反。
        return inExpression.isNot() ? !matched : matched;
    }

    private List<Value> evaluateInCandidates(Expression rightExpression, Tuple tuple, EvalContext context)
            throws DBException {
        // IN (SELECT ...)：执行子查询，并取每一行的第一列作为候选值。
        if (rightExpression instanceof ParenthesedSelect parenthesedSelect) {
            return executeValueSubquery(parenthesedSelect.getSelect(), tuple, context);
        }
        // 兼容解析器直接给出 Select 的情况。
        if (rightExpression instanceof Select select) {
            return executeValueSubquery(select, tuple, context);
        }
        // IN (v1, v2, ...)：逐个计算列表里的表达式作为候选值。
        if (rightExpression instanceof ExpressionList<?> expressionList) {
            ArrayList<Value> values = new ArrayList<>();
            for (Expression expression : expressionList.getExpressions()) {
                values.add(evaluateExpression(expression, tuple, context));
            }
            return values;
        }
        throw new DBException(ExceptionTypes.UnsupportedExpression(rightExpression));
    }


    //执行 EXISTS 里的子查询，看结果是否为空。EXISTS = true/false
    private boolean evaluateExistsExpression(Tuple tuple, ExistsExpression existsExpression, EvalContext context)
            throws DBException {
        // EXISTS / NOT EXISTS 的右侧必须是一个子查询。
        Expression rightExpression = existsExpression.getRightExpression();
        Select select;
        if (rightExpression instanceof ParenthesedSelect parenthesedSelect) {
            select = parenthesedSelect.getSelect();
        } else if (rightExpression instanceof Select directSelect) {
            select = directSelect;
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(rightExpression));
        }

        // EXISTS 判断子查询结果是否非空；NOT EXISTS 在该结果上取反。
        boolean exists = executeExistsSubquery(select, tuple, context);
        return existsExpression.isNot() ? !exists : exists;
    }

    private List<Value> executeValueSubquery(Select select, Tuple tuple, EvalContext context) throws DBException {
        if (context.dbManager == null) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(select));
        }
        ArrayList<Value> values = new ArrayList<>();
        // 关联子查询需要把当前外层 tuple 放进 context，供子查询表达式解析外层列。
        for (Tuple row : executeSubquery(select, context.withOuterTuple(tuple))) {
            Value[] rowValues = row.getValues();
            if (rowValues.length > 0) {
                // IN 子查询当前只取第一列作为比较集合。
                values.add(rowValues[0]);
            }
        }
        return values;
    }

    private boolean executeExistsSubquery(Select select, Tuple tuple, EvalContext context) throws DBException {
        if (context.dbManager == null) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(select));
        }
        // EXISTS 只关心有没有至少一行结果，不关心具体输出值。
        boolean exists = !executeSubquery(select, context.withOuterTuple(tuple)).isEmpty();
        return exists;
    }

    private List<Tuple> executeSubquery(Select select, EvalContext context) throws DBException {
        // 子查询沿用普通 SELECT 的规划流程：先逻辑计划，再生成物理计划。
        LogicalOperator logicalOperator = LogicalPlanner.handleSelect(context.dbManager, select);
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(context.dbManager, logicalOperator,
                context.outerTuple);
        ArrayList<Tuple> rows = new ArrayList<>();
        physicalOperator.Begin();
        try {
            // 执行物理计划并把结果物化成列表，供 IN/EXISTS 判断。
            while (physicalOperator.hasNext()) {
                physicalOperator.Next();
                Tuple current = physicalOperator.Current();
                if (current != null) {
                    rows.add(current);
                }
            }
        } finally {
            physicalOperator.Close();
        }
        return rows;
    }

    private Value resolveColumnValue(Tuple tuple, Column column, Tuple outerTuple) throws DBException {
        // 先在当前 tuple 中解析列名；子查询内部列优先。
        Value currentValue = resolveColumnValueFromTuple(tuple, column);
        if (currentValue != null) {
            return currentValue;
        }
        // 当前 tuple 找不到时，再尝试从外层 tuple 中解析，用于关联子查询。
        if (outerTuple != null) {
            return resolveColumnValueFromTuple(outerTuple, column);
        }
        return null;
    }

    private Value resolveColumnValueFromTuple(Tuple tuple, Column column) throws DBException {
        String tableName = column.getTableName();
        if (tableName == null || tableName.isEmpty()) {
            tableName = findTableNameForColumn(tuple, column.getColumnName());
        }
        if ((tableName == null || tableName.isEmpty()) && tuple instanceof TableTuple tableTuple) {
            tableName = tableTuple.getTableName();
        }
        return tuple.getValue(new TabCol(tableName, column.getColumnName()));
    }

}
