package edu.sustech.cs307;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.tuple.Tuple;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.TerminalBuilder;
import org.pmw.tinylog.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DBEntry {
    public static final String DB_NAME = "CS307-DB";
    public static final int POOL_SIZE = 256 * 512;

    public static void printHelp() {
        Logger.info("Type 'exit' to exit the program.");
        Logger.info("Type 'help' to see this message again.");
        Logger.info("End a SQL statement with ';'. Multi-line SQL is supported.");
        Logger.info("Type 'source <file.sql>' to execute a SQL script.");
    }

    public static void main(String[] args) throws DBException {
        Logger.getConfiguration().formatPattern("{date: HH:mm:ss.SSS} {level}: {message}").activate();

        Logger.info("Hello, This is CS307-DB!");
        Logger.info("Initializing...");
        DBManager dbManager;
        try {
            Map<String, Integer> diskManagerMeta = new HashMap<>(DiskManager.read_disk_manager_meta());
            DiskManager diskManager = new DiskManager(DB_NAME, diskManagerMeta);
            BufferPool bufferPool = new BufferPool(POOL_SIZE, diskManager);
            RecordManager recordManager = new RecordManager(diskManager, bufferPool);
            MetaManager metaManager = new MetaManager(DB_NAME + "/meta");
            dbManager = new DBManager(diskManager, bufferPool, recordManager, metaManager);
        } catch (DBException e) {
            Logger.error(e.getMessage());
            Logger.error("An error occurred during initializing. Exiting.");
            return;
        }

        try {
            LineReader scanner = LineReaderBuilder.builder()
                    .terminal(TerminalBuilder.builder().dumb(true).build())
                    .build();
            boolean running = true;
            while (running) {
                String sql;
                try {
                    sql = readStatement(scanner);
                    if (sql == null) {
                        continue;
                    }
                    if (sql.equalsIgnoreCase("exit")) {
                        running = false;
                        continue;
                    }
                    if (sql.equalsIgnoreCase("help")) {
                        printHelp();
                        continue;
                    }
                    if (sql.toLowerCase().startsWith("source ")) {
                        executeScript(dbManager, sql.substring("source ".length()).trim());
                        continue;
                    }
                } catch (Exception e) {
                    Logger.error("Failed to read command: {}", e.getMessage());
                    continue;
                }
                executeSql(dbManager, sql);
            }
        } catch (Exception e) {
            dbManager.getBufferPool().FlushAllPages("");
            Logger.error("Some error occurred. Exiting after persisting data: {}", e.getMessage());
        }
    }

    private static String readStatement(LineReader scanner) {
        StringBuilder statement = new StringBuilder();
        while (true) {
            Logger.info(statement.isEmpty() ? "CS307-DB> " : "       ...> ");
            String line = scanner.readLine();
            if (line == null) {
                return null;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (statement.isEmpty() && isMetaCommand(line)) {
                return trimTrailingSemicolon(line);
            }
            statement.append(line).append(' ');
            if (line.endsWith(";")) {
                return trimTrailingSemicolon(statement.toString());
            }
        }
    }

    private static boolean isMetaCommand(String line) {
        return line.equalsIgnoreCase("exit")
                || line.equalsIgnoreCase("help")
                || line.toLowerCase().startsWith("source ");
    }

    private static void executeScript(DBManager dbManager, String fileName) {
        Path script = Path.of(fileName);
        if (!Files.exists(script)) {
            script = Path.of(System.getProperty("user.dir"), fileName);
        }
        try {
            List<String> lines = Files.readAllLines(script);
            StringBuilder statement = new StringBuilder();
            int executed = 0;
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("--")) {
                    continue;
                }
                statement.append(line).append(' ');
                if (line.endsWith(";")) {
                    executeSql(dbManager, trimTrailingSemicolon(statement.toString()));
                    statement.setLength(0);
                    executed++;
                }
            }
            if (!statement.isEmpty()) {
                executeSql(dbManager, statement.toString().trim());
                executed++;
            }
            Logger.info("Executed {} statement(s) from {}", executed, script);
        } catch (Exception e) {
            Logger.error("Failed to execute script {}: {}", script, e.getMessage());
        }
    }

    private static String trimTrailingSemicolon(String sql) {
        String result = sql.trim();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static void executeSql(DBManager dbManager, String sql) {
        PhysicalOperator physicalOperator = null;
        try {
            // 逻辑规划：DELETE 在这里生成 LogicalDeleteOperator。
            LogicalOperator operator = LogicalPlanner.resolveAndPlan(dbManager, sql);
            if (operator == null) {
                return;
            }
            // 物理规划：LogicalDeleteOperator 映射成 DeleteOperator。
            physicalOperator = PhysicalPlanner.generateOperator(dbManager, operator);
            if (physicalOperator == null) {
                Logger.info(operator);
                return;
            }
            // 执行算子：DELETE 的 Begin() 完成实际删除。
            printResult(physicalOperator);
            // 持久化：把 dirty page 刷回磁盘。
            dbManager.getBufferPool().FlushAllPages("");
        } catch (DBException e) {
            Logger.error(e.getMessage());
            Logger.error("Command failed. The database is still running.");
        } catch (Exception e) {
            Logger.error("Unexpected error while executing SQL: {}", e.getMessage());
        } finally {
            if (physicalOperator != null) {
                try {
                    physicalOperator.Close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void printResult(PhysicalOperator physicalOperator) throws DBException {
        Logger.info(getSeparator(physicalOperator.outputSchema().size()));
        Logger.info(getHeaderString(physicalOperator.outputSchema()));
        Logger.info(getSeparator(physicalOperator.outputSchema().size()));
        // 火山模型入口：调用物理算子的 Begin()。
        physicalOperator.Begin();
        while (physicalOperator.hasNext()) {
            physicalOperator.Next();
            Tuple tuple = physicalOperator.Current();
            Logger.info(getRecordString(tuple));
            Logger.info(getSeparator(physicalOperator.outputSchema().size()));
        }
    }

    private static String getHeaderString(ArrayList<ColumnMeta> columnMetas) {
        StringBuilder header = new StringBuilder("|");
        for (ColumnMeta entry : columnMetas) {
            String tabCol = String.format("%s.%s", entry.tableName, entry.name);
            header.append(StringUtils.center(tabCol, 15, ' ')).append("|");
        }
        return header.toString();
    }

    private static String getRecordString(Tuple tuple) throws DBException {
        StringBuilder tupleString = new StringBuilder("|");
        for (ValuePrinter entry : valuesOf(tuple)) {
            tupleString.append(StringUtils.center(entry.value, 15, ' ')).append("|");
        }
        return tupleString.toString();
    }

    private static List<ValuePrinter> valuesOf(Tuple tuple) throws DBException {
        ArrayList<ValuePrinter> result = new ArrayList<>();
        for (Object entry : tuple.getValues()) {
            result.add(new ValuePrinter(String.valueOf(entry)));
        }
        return result;
    }

    private static String getSeparator(int width) {
        StringBuilder line = new StringBuilder("+");
        for (int i = 0; i < width; i++) {
            line.append("---------------+");
        }
        return line.toString();
    }

    private record ValuePrinter(String value) {
    }
}
