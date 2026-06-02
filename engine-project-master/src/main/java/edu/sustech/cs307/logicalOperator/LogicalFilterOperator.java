package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.expression.Expression;

import java.util.Collections;

public class LogicalFilterOperator extends LogicalOperator {
    private final Expression condition;
    private final LogicalOperator child;

    public LogicalFilterOperator(LogicalOperator child, Expression condition) {
        super(Collections.singletonList(child));
        this.child = child;
        this.condition = condition;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public Expression getWhereExpr() {
        return condition;
    }

    @Override
    public String toString() {
        return formatUnaryTree("LogicalFilterOperator(condition=" + condition + ")", child);
    }
}
