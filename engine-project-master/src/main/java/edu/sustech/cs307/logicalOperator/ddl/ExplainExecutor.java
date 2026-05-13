package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;

import net.sf.jsqlparser.statement.ExplainStatement;
import org.pmw.tinylog.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class ExplainExecutor implements DMLExecutor {

    private final ExplainStatement explainStatement;
    private final DBManager dbManager;

    public ExplainExecutor(ExplainStatement explainStatement, DBManager dbManager) {
        this.explainStatement = explainStatement;
        this.dbManager = dbManager;
    }

    @Override
    public void execute() throws DBException {
       if (explainStatement.getStatement() == null) {
           throw new DBException(ExceptionTypes.UnsupportedCommand(explainStatement.toString()));
       }
       LogicalOperator logicalOperator = LogicalPlanner.handleSelect(dbManager, explainStatement.getStatement());
       PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
       Logger.info(formatPhysicalPlan(physicalOperator, ""));
    }

    private String formatPhysicalPlan(PhysicalOperator operator, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append(operator.getClass().getSimpleName());
        List<PhysicalOperator> children = getChildOperators(operator);
        for (PhysicalOperator child : children) {
            sb.append("\n").append(formatPhysicalPlan(child, indent + "  "));
        }
        return sb.toString();
    }

    private List<PhysicalOperator> getChildOperators(PhysicalOperator operator) {
        ArrayList<PhysicalOperator> children = new ArrayList<>();
        for (Field field : operator.getClass().getDeclaredFields()) {
            if (!PhysicalOperator.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            try {
                PhysicalOperator child = (PhysicalOperator) field.get(operator);
                if (child != null) {
                    children.add(child);
                }
            } catch (IllegalAccessException ignored) {
                // Best-effort explain output should not fail query planning.
            }
        }
        return children;
    }
}
