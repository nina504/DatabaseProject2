package edu.sustech.cs307.logicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogicalProjectOperator extends LogicalOperator {

    private final List<SelectItem<?>> selectItems;
    private final LogicalOperator child;

    public LogicalProjectOperator(LogicalOperator child, List<SelectItem<?>> selectItems) {
        super(Collections.singletonList(child));
        this.child = child;
        this.selectItems = selectItems;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public List<SelectItem<?>> getSelectItems() {
        return selectItems;
    }

    public List<TabCol> getOutputSchema() throws DBException {
        List<TabCol> outputSchema = new ArrayList<>();
        for (SelectItem<?> selectItem : selectItems) {
            if (selectItem.getExpression() instanceof AllColumns) {
                outputSchema.add(new TabCol("*", "*"));
            } else if (selectItem.getExpression() instanceof Column column) {
                String tableName = column.getTableName();
                if (tableName == null || tableName.isEmpty()) {
                    tableName = inferSingleTableName(child);
                }
                outputSchema.add(new TabCol(tableName, column.getColumnName()));
            } else {
                throw new DBException(ExceptionTypes.NotSupportedOperation(selectItem.getExpression()));
            }
        }
        return outputSchema;
    }

    private String inferSingleTableName(LogicalOperator operator) {
        if (operator instanceof LogicalTableScanOperator tableScanOperator) {
            return tableScanOperator.getTableName();
        }
        if (operator.getChild() != null) {
            return inferSingleTableName(operator.getChild());
        }
        return "";
    }

    @Override
    public String toString() {
        return formatUnaryTree("ProjectOperator(selectItems=" + selectItems + ")", child);
    }
}
