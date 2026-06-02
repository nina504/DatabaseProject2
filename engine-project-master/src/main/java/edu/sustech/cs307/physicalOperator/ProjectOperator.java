package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.ProjectTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;
import java.util.List;

public class ProjectOperator implements PhysicalOperator {
    private PhysicalOperator child;
    private List<TabCol> outputSchema; // Use bounded wildcard
    private Tuple currentTuple;

    public ProjectOperator(PhysicalOperator child, List<TabCol> outputSchema) { // Use bounded wildcard
        this.child = child;
        this.outputSchema = outputSchema;
        if (this.outputSchema.size() == 1 && this.outputSchema.get(0).getTableName().equals("*")) {
            List<TabCol> newOutputSchema = new ArrayList<>();
            for (ColumnMeta tabCol : child.outputSchema()) {
                newOutputSchema.add(new TabCol(tabCol.tableName, tabCol.name));
            }
            this.outputSchema = newOutputSchema;
        }
    }

    @Override
    public boolean hasNext() throws DBException {
        return child.hasNext();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
    }

    @Override
    public void Next() throws DBException {
        if (hasNext()) {
            child.Next();
            Tuple inputTuple = child.Current();
            if (inputTuple != null) {

                currentTuple = new ProjectTuple(inputTuple, outputSchema); // Create ProjectTuple
            } else {
                currentTuple = null;
            }
        } else {
            currentTuple = null;
        }
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        child.Close();
        currentTuple = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> result = new ArrayList<>();
        for (TabCol tabCol : outputSchema) {
            ColumnMeta matched = null;
            for (ColumnMeta columnMeta : child.outputSchema()) {
                boolean tableMatches = tabCol.getTableName() == null
                        || tabCol.getTableName().isEmpty()
                        || columnMeta.tableName.equalsIgnoreCase(tabCol.getTableName());
                boolean columnMatches = columnMeta.name.equalsIgnoreCase(tabCol.getColumnName());
                if (tableMatches && columnMatches) {
                    matched = columnMeta;
                    break;
                }
            }
            if (matched != null) {
                result.add(matched);
            }
        }
        return result;
    }
    @Override
    public String toString() {
        return PlanTreeFormatter.formatUnaryTree("ProjectOperator(outputSchema=" + outputSchema + ")", child);
    }
}
