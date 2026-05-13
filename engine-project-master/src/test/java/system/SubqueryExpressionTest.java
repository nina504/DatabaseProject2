package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.tuple.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubqueryExpressionTest {

    @TempDir
    Path tempDir;

    @Test
    void supportsInNotInAndCorrelatedExists() throws DBException {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE t (id int, age int)");
        executeStatement(dbManager, "CREATE TABLE u (id int)");
        executeStatement(dbManager, "INSERT INTO t(id, age) VALUES (1, 18), (2, 20), (3, 21)");
        executeStatement(dbManager, "INSERT INTO u(id) VALUES (1), (3)");

        assertThat(selectFirstColumn(dbManager, "SELECT id FROM t WHERE id IN (SELECT id FROM u)"))
                .containsExactly(1L, 3L);
        assertThat(selectFirstColumn(dbManager, "SELECT id FROM t WHERE id NOT IN (SELECT id FROM u)"))
                .containsExactly(2L);
        assertThat(selectFirstColumn(dbManager,
                "SELECT id FROM t WHERE EXISTS (SELECT * FROM u WHERE u.id = t.id)"))
                .containsExactly(1L, 3L);
        assertThat(selectFirstColumn(dbManager,
                "SELECT id FROM t WHERE NOT EXISTS (SELECT * FROM u WHERE u.id = t.id)"))
                .containsExactly(2L);
    }

    private DBManager buildDbManager() throws DBException {
        DiskManager diskManager = new DiskManager(tempDir.toString(), new HashMap<>());
        BufferPool bufferPool = new BufferPool(16, diskManager);
        RecordManager recordManager = new RecordManager(diskManager, bufferPool);
        MetaManager metaManager = new MetaManager(tempDir.resolve("meta").toString());
        return new DBManager(diskManager, bufferPool, recordManager, metaManager);
    }

    private void executeStatement(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        if (logicalOperator == null) {
            return;
        }
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        physicalOperator.Begin();
        while (physicalOperator.hasNext()) {
            physicalOperator.Next();
            physicalOperator.Current();
        }
        physicalOperator.Close();
        dbManager.getBufferPool().FlushAllPages("");
    }

    private List<Long> selectFirstColumn(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        ArrayList<Long> values = new ArrayList<>();
        physicalOperator.Begin();
        while (physicalOperator.hasNext()) {
            physicalOperator.Next();
            Tuple tuple = physicalOperator.Current();
            values.add((Long) tuple.getValues()[0].value);
        }
        physicalOperator.Close();
        return values;
    }
}
