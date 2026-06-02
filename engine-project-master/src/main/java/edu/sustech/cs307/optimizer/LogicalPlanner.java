package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.expression.Function;

import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.exception.DBException;

public class LogicalPlanner {
    private static final Pattern BEGIN_PATTERN = Pattern.compile("(?i)^BEGIN(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern START_TRANSACTION_PATTERN = Pattern.compile("(?i)^START\\s+TRANSACTION$");
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("(?i)^ROLLBACK$");
    private static final Pattern SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^SAVEPOINT\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ROLLBACK_TO_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^ROLLBACK\\s+TO(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern RELEASE_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^RELEASE(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern SHOW_TABLES_PATTERN = Pattern.compile("(?i)^SHOW\\s+TABLES$");
    private static final Pattern DESCRIBE_PATTERN =
            Pattern.compile("(?i)^(?:DESCRIBE|DESC)\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern DROP_TABLE_PATTERN =
            Pattern.compile("(?i)^DROP\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern CREATE_INDEX_PATTERN =
            Pattern.compile("(?i)^CREATE\\s+INDEX\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+ON\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)$");
    private static final Pattern DROP_INDEX_PATTERN =
            Pattern.compile("(?i)^DROP\\s+INDEX\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern PRINT_INDEX_PATTERN =
            Pattern.compile("(?i)^(?:PRINT|SHOW)\\s+INDEX\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    // 部分支持 ALTER TABLE：这里目前只识别 ADD COLUMN 和 DROP COLUMN。
    // 其他 ALTER 形式暂不支持，也不会被转换成逻辑算子。
    private static final Pattern ALTER_ADD_COLUMN_PATTERN =
            Pattern.compile("(?i)^ALTER\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+ADD(?:\\s+COLUMN)?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(INT|INTEGER|VARCHAR|CHAR|DOUBLE|FLOAT)$");
    private static final Pattern ALTER_DROP_COLUMN_PATTERN =
            Pattern.compile("(?i)^ALTER\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+DROP(?:\\s+COLUMN)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");

    public static LogicalOperator resolveAndPlan(DBManager dbManager, String sql) throws DBException {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        if (handleManualDDLCommand(dbManager, sql)) {
            return null;
        }
        if (handleManualTransactionCommand(dbManager, sql)) {
            return null;
        }
        JSqlParser parser = new CCJSqlParserManager();
        Statement stmt = null;
        try {
            stmt = parser.parse(new StringReader(sql));
        } catch (JSQLParserException e) {
            throw new DBException(ExceptionTypes.InvalidSQL(sql, e.getMessage()));
        }
        LogicalOperator operator = null;
        // Query
        if (stmt instanceof Select selectStmt) {
            operator = handleSelect(dbManager, selectStmt);
        } else if (stmt instanceof Insert insertStmt) {
            operator = handleInsert(dbManager, insertStmt);
        } else if (stmt instanceof Update updateStmt) {
            operator = handleUpdate(dbManager, updateStmt);
        } else if (stmt instanceof Delete deleteStmt) {
            // 识别 DELETE 语句。
            operator = handleDelete(dbManager, deleteStmt);
        }else if (stmt instanceof Commit) {
            dbManager.commitTransaction();
            return null;
        }
        // functional
        else if (stmt instanceof CreateTable createTableStmt) {
            CreateTableExecutor createTable = new CreateTableExecutor(createTableStmt, dbManager, sql);
            createTable.execute();
            return null;
        } else if (stmt instanceof ExplainStatement explainStatement) {
            ExplainExecutor explainExecutor = new ExplainExecutor(explainStatement, dbManager);
            explainExecutor.execute();
            return null;
        } else if (stmt instanceof ShowStatement showStatement) {
            ShowDatabaseExecutor showDatabaseExecutor = new ShowDatabaseExecutor(showStatement, dbManager);
            showDatabaseExecutor.execute();
            return null;
        } else {
            throw new DBException(ExceptionTypes.UnsupportedCommand((stmt.toString())));
        }
        return operator;
    }


    public static LogicalOperator handleSelect(DBManager dbManager, Select selectStmt) throws DBException {
        PlainSelect plainSelect = selectStmt.getPlainSelect();
        if (plainSelect.getFromItem() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand((plainSelect.toString())));
        }
        LogicalOperator root = new LogicalTableScanOperator(plainSelect.getFromItem().toString(), dbManager);

        int depth = 0;
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                // 识别 JOIN：左侧使用当前 root，右侧生成新的表扫描。
                root = new LogicalJoinOperator(
                        root,
                        new LogicalTableScanOperator(join.getRightItem().toString(), dbManager),
                        join.getOnExpressions(),
                        depth);
                depth += 1;
            }
        }

        // 在 Join 之后应用 Filter，Filter 的输入是 Join 的结果 (root)
        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }
        // 简单 count 查询：先保留 WHERE 过滤后的输入，再生成 LogicalCountOperator。
        if (isCountSelect(plainSelect) && plainSelect.getGroupBy() == null) {
            return new LogicalCountOperator(root);
        }
        boolean aggregateSelect = isAggregateSelect(plainSelect);
        if (aggregateSelect) {
            // 聚合查询：保存 selectItems 和 GROUP BY 字段。
            root = new LogicalAggregateOperator(root, plainSelect.getSelectItems(), getGroupByExpressions(plainSelect));
        }
        if (plainSelect.getOrderByElements() != null && !plainSelect.getOrderByElements().isEmpty()) {
            root = new LogicalSortOperator(root, plainSelect.getOrderByElements());
        }
        if (!aggregateSelect) {
            root = new LogicalProjectOperator(root, plainSelect.getSelectItems());
        }
        return root;
    }

    private static LogicalOperator handleInsert(DBManager dbManager, Insert insertStmt) {
        return new LogicalInsertOperator(insertStmt.getTable().getName(), insertStmt.getColumns(),
                insertStmt.getValues());
    }

    private static LogicalOperator handleUpdate(DBManager dbManager, Update updateStmt) throws DBException {
        LogicalOperator root = new LogicalTableScanOperator(updateStmt.getTable().getName(), dbManager);
        return new LogicalUpdateOperator(root, updateStmt.getTable().getName(), updateStmt.getUpdateSets(),
                updateStmt.getWhere());
    }

    private static LogicalOperator handleDelete(DBManager dbManager, Delete deleteStmt) throws DBException {
        String tableName = deleteStmt.getTable().getName();
        // DELETE 先以目标表扫描作为输入。
        LogicalOperator root = new LogicalTableScanOperator(tableName, dbManager);
        // 保存表名和 WHERE 条件，生成 LogicalDeleteOperator。
        return new LogicalDeleteOperator(root, tableName, deleteStmt.getWhere());
    }

    private static boolean isCountSelect(PlainSelect plainSelect) {
        // count(*) 不是普通投影，需要单独识别为计数算子。
        if (plainSelect.getSelectItems() == null || plainSelect.getSelectItems().size() != 1) {
            return false;
        }
        var expression = plainSelect.getSelectItems().get(0).getExpression();
        if (expression instanceof Function function) {
            return function.getName() != null && function.getName().equalsIgnoreCase("count");
        }
        return false;
    }

    private static boolean isAggregateSelect(PlainSelect plainSelect) {
        // 识别 GROUP BY 或 min/max/sum/avg/count 聚合函数。
        if (plainSelect.getGroupBy() != null) {
            return true;
        }
        if (plainSelect.getSelectItems() == null) {
            return false;
        }
        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            if (selectItem.getExpression() instanceof Function function) {
                String functionName = function.getName();
                if (functionName != null
                        && (functionName.equalsIgnoreCase("min")
                        || functionName.equalsIgnoreCase("max")
                        || functionName.equalsIgnoreCase("sum")
                        || functionName.equalsIgnoreCase("avg")
                        || functionName.equalsIgnoreCase("count"))) {
                    return true;
                }
            }
        }
        return false;
    }//识别 min/max/sum/avg/count，或者只要有 GROUP BY 就认为是聚合查询

    private static List<Expression> getGroupByExpressions(PlainSelect plainSelect) {
        // 提取 GROUP BY 后面的表达式列表。
        if (plainSelect.getGroupBy() == null || plainSelect.getGroupBy().getGroupByExpressionList() == null) {
            return List.of();
        }
        return new ArrayList<>(plainSelect.getGroupBy().getGroupByExpressionList().getExpressions());
    }
    private static String normalizeSql(String sql) {
        String normalizedSql = sql == null ? "" : sql.trim();
        while (normalizedSql.endsWith(";")) {
            normalizedSql = normalizedSql.substring(0, normalizedSql.length() - 1).trim();
        }
        return normalizedSql;
    }

    private static boolean handleManualTransactionCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (BEGIN_PATTERN.matcher(normalizedSql).matches() || START_TRANSACTION_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.beginTransaction();
            return true;
        }
        if (ROLLBACK_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.getTransactionManager().rollback();
            return true;
        }
        Matcher savepointMatcher = SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (savepointMatcher.matches()) {
            dbManager.getTransactionManager().savepoint(savepointMatcher.group(1));
            return true;
        }
        Matcher rollbackToMatcher = ROLLBACK_TO_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (rollbackToMatcher.matches()) {
            dbManager.getTransactionManager().rollbackToSavepoint(rollbackToMatcher.group(1));
            return true;
        }
        Matcher releaseMatcher = RELEASE_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (releaseMatcher.matches()) {
            dbManager.getTransactionManager().releaseSavepoint(releaseMatcher.group(1));
            return true;
        }
        return false;
    }

    private static boolean handleManualDDLCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (SHOW_TABLES_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.showTables();
            return true;
        }
        Matcher describeMatcher = DESCRIBE_PATTERN.matcher(normalizedSql);
        if (describeMatcher.matches()) {
            dbManager.descTable(describeMatcher.group(1));
            return true;
        }
        Matcher dropMatcher = DROP_TABLE_PATTERN.matcher(normalizedSql);
        if (dropMatcher.matches()) {
            dbManager.dropTable(dropMatcher.group(1));
            return true;
        }
        Matcher createIndexMatcher = CREATE_INDEX_PATTERN.matcher(normalizedSql);
        if (createIndexMatcher.matches()) {
            dbManager.createIndex(createIndexMatcher.group(1), createIndexMatcher.group(2), createIndexMatcher.group(3));
            return true;
        }
        Matcher dropIndexMatcher = DROP_INDEX_PATTERN.matcher(normalizedSql);
        if (dropIndexMatcher.matches()) {
            dbManager.dropIndex(dropIndexMatcher.group(1));
            return true;
        }
        Matcher printIndexMatcher = PRINT_INDEX_PATTERN.matcher(normalizedSql);
        if (printIndexMatcher.matches()) {
            dbManager.printIndex(printIndexMatcher.group(1));
            return true;
        }
        Matcher alterAddColumnMatcher = ALTER_ADD_COLUMN_PATTERN.matcher(normalizedSql);
        if (alterAddColumnMatcher.matches()) {
            // ALTER TABLE t ADD COLUMN c TYPE 直接执行：更新元数据并重写数据文件。
            dbManager.addColumn(alterAddColumnMatcher.group(1), alterAddColumnMatcher.group(2),
                    alterAddColumnMatcher.group(3));
            return true;
        }
        Matcher alterDropColumnMatcher = ALTER_DROP_COLUMN_PATTERN.matcher(normalizedSql);
        if (alterDropColumnMatcher.matches()) {
            // ALTER TABLE t DROP COLUMN c 也直接处理，不生成物理算子。
            dbManager.dropColumn(alterDropColumnMatcher.group(1), alterDropColumnMatcher.group(2));
            return true;
        }
        return false;
    }


}
