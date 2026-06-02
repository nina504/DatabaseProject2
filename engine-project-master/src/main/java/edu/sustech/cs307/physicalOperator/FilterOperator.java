package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.Tuple;
import net.sf.jsqlparser.expression.Expression;

import java.util.ArrayList;
import java.util.Collection;

public class FilterOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final Expression whereExpr;
    private final DBManager dbManager;
    private final Tuple outerTuple;
    private Tuple currentTuple;
    private boolean isOpen = false;
    private boolean readyForNext = false;

    public FilterOperator(PhysicalOperator child, Expression whereExpr) {
        this(child, whereExpr, null, null);
    }

    public FilterOperator(PhysicalOperator child, Expression whereExpr, DBManager dbManager) {
        this(child, whereExpr, dbManager, null);
    }

    public FilterOperator(PhysicalOperator child, Expression whereExpr, DBManager dbManager, Tuple outerTuple) {
        this.child = child;
        this.whereExpr = whereExpr;
        this.dbManager = dbManager;
        this.outerTuple = outerTuple;
    }

    public FilterOperator(PhysicalOperator child, Collection<Expression> whereExpr) {
        this(child, whereExpr.iterator().next(), null, null);
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        isOpen = true;
        currentTuple = null;
        readyForNext = false;
    }

    @Override
    public boolean hasNext() throws DBException {
        if (!isOpen) {
            return false;
        }
        if (!readyForNext) {
            return findNext();
        }
        return currentTuple != null;
    }

    @Override
    public void Next() throws DBException {
        if (!isOpen) {
            return;
        }
        if (!readyForNext) {
            hasNext();
        }
        readyForNext = false;
    }

    private boolean findNext() throws DBException {
        currentTuple = null;
        Tuple.EvalContext context = new Tuple.EvalContext(dbManager, outerTuple);
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple != null && tuple.eval_expr(whereExpr, context)) {
                currentTuple = tuple;
                readyForNext = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        child.Close();
        isOpen = false;
        currentTuple = null;
        readyForNext = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return child.outputSchema();
    }
    @Override
    public String toString() {
        return PlanTreeFormatter.formatUnaryTree("FilterOperator(condition=" + whereExpr + ")", child);
    }
}
