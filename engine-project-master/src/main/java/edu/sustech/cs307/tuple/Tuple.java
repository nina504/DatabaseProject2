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
        } else if (whereExpr instanceof BinaryExpression binaryExpression) {
            return evaluateBinaryExpression(tuple, binaryExpression, context);
        } else {
            return true;
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
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
        }
    }

    private boolean evaluateInExpression(Tuple tuple, InExpression inExpression, EvalContext context) throws DBException {
        Value leftValue = evaluateExpression(inExpression.getLeftExpression(), tuple, context);
        if (leftValue == null) {
            return false;
        }
        List<Value> candidates = evaluateInCandidates(inExpression.getRightExpression(), tuple, context);
        boolean matched = false;
        for (Value candidate : candidates) {
            if (ValueComparer.compare(leftValue, candidate) == 0) {
                matched = true;
                break;
            }
        }
        return inExpression.isNot() ? !matched : matched;
    }

    private List<Value> evaluateInCandidates(Expression rightExpression, Tuple tuple, EvalContext context)
            throws DBException {
        if (rightExpression instanceof ParenthesedSelect parenthesedSelect) {
            return executeValueSubquery(parenthesedSelect.getSelect(), tuple, context);
        }
        if (rightExpression instanceof Select select) {
            return executeValueSubquery(select, tuple, context);
        }
        if (rightExpression instanceof ExpressionList<?> expressionList) {
            ArrayList<Value> values = new ArrayList<>();
            for (Expression expression : expressionList.getExpressions()) {
                values.add(evaluateExpression(expression, tuple, context));
            }
            return values;
        }
        throw new DBException(ExceptionTypes.UnsupportedExpression(rightExpression));
    }

    private boolean evaluateExistsExpression(Tuple tuple, ExistsExpression existsExpression, EvalContext context)
            throws DBException {
        Expression rightExpression = existsExpression.getRightExpression();
        Select select;
        if (rightExpression instanceof ParenthesedSelect parenthesedSelect) {
            select = parenthesedSelect.getSelect();
        } else if (rightExpression instanceof Select directSelect) {
            select = directSelect;
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(rightExpression));
        }

        boolean exists = executeExistsSubquery(select, tuple, context);
        return existsExpression.isNot() ? !exists : exists;
    }

    private List<Value> executeValueSubquery(Select select, Tuple tuple, EvalContext context) throws DBException {
        if (context.dbManager == null) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(select));
        }
        ArrayList<Value> values = new ArrayList<>();
        for (Tuple row : executeSubquery(select, context.withOuterTuple(tuple))) {
            Value[] rowValues = row.getValues();
            if (rowValues.length > 0) {
                values.add(rowValues[0]);
            }
        }
        return values;
    }

    private boolean executeExistsSubquery(Select select, Tuple tuple, EvalContext context) throws DBException {
        if (context.dbManager == null) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(select));
        }
        boolean exists = !executeSubquery(select, context.withOuterTuple(tuple)).isEmpty();
        return exists;
    }

    private List<Tuple> executeSubquery(Select select, EvalContext context) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.handleSelect(context.dbManager, select);
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(context.dbManager, logicalOperator,
                context.outerTuple);
        ArrayList<Tuple> rows = new ArrayList<>();
        physicalOperator.Begin();
        try {
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
        Value currentValue = resolveColumnValueFromTuple(tuple, column);
        if (currentValue != null) {
            return currentValue;
        }
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
