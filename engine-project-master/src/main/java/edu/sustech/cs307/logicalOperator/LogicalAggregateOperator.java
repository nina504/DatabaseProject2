package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.Collections;
import java.util.List;

public class LogicalAggregateOperator extends LogicalOperator {
    private final LogicalOperator child;
    private final List<SelectItem<?>> selectItems;
    private final List<Expression> groupByExpressions;

    public LogicalAggregateOperator(LogicalOperator child, List<SelectItem<?>> selectItems,
                                    List<Expression> groupByExpressions) {
        super(Collections.singletonList(child));
        this.child = child;
        this.selectItems = selectItems;
        this.groupByExpressions = groupByExpressions;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public List<SelectItem<?>> getSelectItems() {
        return selectItems;
    }

    public List<Expression> getGroupByExpressions() {
        return groupByExpressions;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AggregateOperator(selectItems=").append(selectItems)
                .append(", groupBy=").append(groupByExpressions).append(")");
        String[] childLines = child.toString().split("\\R");
        if (childLines.length > 0) {
            sb.append("\n`-- ").append(childLines[0]);
            for (int i = 1; i < childLines.length; i++) {
                sb.append("\n    ").append(childLines[i]);
            }
        }
        return sb.toString();
    }
}
