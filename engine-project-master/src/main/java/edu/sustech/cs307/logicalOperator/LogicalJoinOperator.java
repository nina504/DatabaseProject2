package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.expression.Expression;

import java.util.Arrays;
import java.util.Collection;

public class LogicalJoinOperator extends LogicalOperator {
    private final Collection<Expression> onExpressions;
    private final LogicalOperator leftInput;
    private final LogicalOperator rightInput;

    public LogicalJoinOperator(LogicalOperator left, LogicalOperator right,
                               Collection<Expression> onExpr,
                               int depth) {
        super(Arrays.asList(left, right));
        // 保存 JOIN 左输入。
        this.leftInput = left;
        // 保存 JOIN 右输入。
        this.rightInput = right;
        // 保存 ON 条件，例如 t.id = u.id。
        this.onExpressions = onExpr;
    }

    public LogicalOperator getLeftInput() {
        return leftInput;
    }

    public LogicalOperator getRightInput() {
        return rightInput;
    }

    public Collection<Expression> getJoinExprs() {
        return onExpressions;
    }

    @Override
    public String toString() {
        return formatBinaryTree("LogicalJoinOperator(condition=" + onExpressions + ")", leftInput, rightInput);
    }
}
