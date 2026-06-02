package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.JoinTuple;
import edu.sustech.cs307.tuple.Tuple;
import net.sf.jsqlparser.expression.Expression;


//Nested Loop Join 的实现是先读取左右输入，再用双重循环枚举每一对左右 tuple，
// 把它们包装成 JoinTuple，用 tuple.eval_expr 判断 ON 等值条件，满足条件的组合就作为 join 结果输出。
public class NestedLoopJoinOperator implements PhysicalOperator {

    private PhysicalOperator leftOperator;
    private PhysicalOperator rightOperator;
    private Collection<Expression> expr;
    private final ArrayList<Tuple> leftTuples = new ArrayList<>();
    private final ArrayList<Tuple> rightTuples = new ArrayList<>();
    private int leftCursor;
    private int rightCursor;
    private Tuple current;
    private TabCol[] outputTupleSchema;

    public NestedLoopJoinOperator(PhysicalOperator leftOperator, PhysicalOperator rightOperator,
            Collection<Expression> expr) {
        // 保存左右输入算子和 JOIN ON 条件。
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
        this.expr = expr;
    }

    @Override
    public boolean hasNext() throws DBException {
        // current 已经准备好时，直接返回 true。
        if (current != null) {
            return true;
        }
        // 双重for循环：对每一行左表 tuple ，都尝试匹配所有右表 tuple。
        while (leftCursor < leftTuples.size()) {
            Tuple leftTuple = leftTuples.get(leftCursor);
            while (rightCursor < rightTuples.size()) {
                Tuple rightTuple = rightTuples.get(rightCursor++);
                // 合并左右 tuple，joinTuple = leftTuple + rightTuple
                JoinTuple joinTuple = new JoinTuple(leftTuple, rightTuple, outputTupleSchema);
                // if ON 条件成立: 输出 joinTuple
                if (matches(joinTuple)) {
                    current = joinTuple;
                    return true;
                }
            }
            leftCursor++;
            rightCursor = 0;
        }
        return false;
    }

    @Override
    public void Begin() throws DBException {
        // 初始化 JOIN 状态。
        leftTuples.clear();
        rightTuples.clear();
        leftCursor = 0;
        rightCursor = 0;
        current = null;
        outputTupleSchema = buildTupleSchema();

        // 读取左输入的全部 tuple。
        leftOperator.Begin();
        while (leftOperator.hasNext()) {
            leftOperator.Next();
            Tuple tuple = leftOperator.Current();
            if (tuple != null) {
                leftTuples.add(tuple);
            }
        }

        // 读取右输入的全部 tuple。
        rightOperator.Begin();
        while (rightOperator.hasNext()) {
            rightOperator.Next();
            Tuple tuple = rightOperator.Current();
            if (tuple != null) {
                rightTuples.add(tuple);
            }
        }
    }

    @Override
    public void Next() throws DBException {
        // 如果还没有准备好 current，就向前寻找下一条匹配结果。
        if (current == null) {
            hasNext();
        }
    }

    @Override
    public Tuple Current() {
        // 返回当前 JOIN 结果，并清空 current，准备下一次查找。
        Tuple result = current;
        current = null;
        return result;
    }

    @Override
    public void Close() {
        // 关闭左右输入并清理缓存。
        leftOperator.Close();
        rightOperator.Close();
        leftTuples.clear();
        rightTuples.clear();
        current = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        // JOIN 输出 schema = 左输入 schema + 右输入 schema。
        ArrayList<ColumnMeta> result = new ArrayList<>();
        result.addAll(leftOperator.outputSchema());
        result.addAll(rightOperator.outputSchema());
        return result;
    }

    private TabCol[] buildTupleSchema() {
        // 构造 JoinTuple 使用的列信息。
        ArrayList<TabCol> schema = new ArrayList<>();
        for (ColumnMeta columnMeta : leftOperator.outputSchema()) {
            schema.add(new TabCol(columnMeta.tableName, columnMeta.name));
        }
        for (ColumnMeta columnMeta : rightOperator.outputSchema()) {
            schema.add(new TabCol(columnMeta.tableName, columnMeta.name));
        }
        return schema.toArray(new TabCol[0]);
    }

    private boolean matches(Tuple tuple) throws DBException {
        // 没有 ON 条件时，所有左右组合都匹配。
        if (expr == null || expr.isEmpty()) {
            return true;
        }
        // 逐个判断 ON 条件；全部满足才输出。
        for (Expression expression : expr) {
            if (!tuple.eval_expr(expression)) {
                return false;
            }
        }
        return true;
    }
    @Override
    public String toString() {
        return PlanTreeFormatter.formatBinaryTree("NestedLoopJoinOperator(condition=" + expr + ")",
                leftOperator, rightOperator);
    }
}
