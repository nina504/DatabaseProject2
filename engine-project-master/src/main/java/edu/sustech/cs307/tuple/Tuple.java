package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.schema.Column;

public abstract class Tuple {
    public abstract Value getValue(TabCol tabCol) throws DBException;

    public abstract TabCol[] getTupleSchema();

    public abstract Value[] getValues() throws DBException;

    public boolean eval_expr(Expression expr) throws DBException {
        return evaluateCondition(this, expr);
    }

    private boolean evaluateCondition(Tuple tuple, Expression whereExpr) {
        if (whereExpr == null) {
            return true;
        }
        if (whereExpr instanceof AndExpression andExpr) {
            return evaluateCondition(tuple, andExpr.getLeftExpression())
                    && evaluateCondition(tuple, andExpr.getRightExpression());
        } else if (whereExpr instanceof OrExpression orExpr) {
            return evaluateCondition(tuple, orExpr.getLeftExpression())
                    || evaluateCondition(tuple, orExpr.getRightExpression());
        } else if (whereExpr instanceof Parenthesis parenthesis) {
            return evaluateCondition(tuple, parenthesis.getExpression());
        } else if (whereExpr instanceof BinaryExpression binaryExpression) {
            return evaluateBinaryExpression(tuple, binaryExpression);
        } else {
            return true;
        }
    }

    private boolean evaluateBinaryExpression(Tuple tuple, BinaryExpression binaryExpr) {
        Expression leftExpr = binaryExpr.getLeftExpression();
        Expression rightExpr = binaryExpr.getRightExpression();
        String operator = binaryExpr.getStringExpression();
        Value leftValue = null;
        Value rightValue = null;

        try {
            if (leftExpr instanceof Column leftColumn) {
                //get table name
                String table_name = leftColumn.getTableName();
                if (table_name == null || table_name.isEmpty()) {
                    table_name = findTableNameForColumn(tuple, leftColumn.getColumnName());
                }
                if ((table_name == null || table_name.isEmpty()) && tuple instanceof TableTuple) {
                    TableTuple tableTuple = (TableTuple) tuple;
                    table_name = tableTuple.getTableName();
                }
                leftValue = tuple.getValue(new TabCol(table_name, leftColumn.getColumnName()));
            } else {
                leftValue = getConstantValue(leftExpr); // Handle constant left value
            }

            if (rightExpr instanceof Column rightColumn) {
                //get table name
                String table_name = rightColumn.getTableName();
                if (table_name == null || table_name.isEmpty()) {
                    table_name = findTableNameForColumn(tuple, rightColumn.getColumnName());
                }
                if ((table_name == null || table_name.isEmpty()) && tuple instanceof TableTuple) {
                    TableTuple tableTuple = (TableTuple) tuple;
                    table_name = tableTuple.getTableName();
                }
                rightValue = tuple.getValue(new TabCol(table_name, rightColumn.getColumnName()));
            } else {
                rightValue = getConstantValue(rightExpr); // Handle constant right value

            }

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

        } catch (DBException e) {
            e.printStackTrace(); // Handle exception properly
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
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        } else if (expr instanceof Column) {
            Column col = (Column) expr;
            String tableName = col.getTableName();
            if (tableName == null || tableName.isEmpty()) {
                tableName = findTableNameForColumn(this, col.getColumnName());
            }
            return getValue(new TabCol(tableName, col.getColumnName()));
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
        }
    }

}
