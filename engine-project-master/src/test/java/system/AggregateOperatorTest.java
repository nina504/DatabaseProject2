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
import edu.sustech.cs307.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateOperatorTest {

    @TempDir
    Path tempDir;

    @Test
    void minMaxAndGroupByUseChildTuples() throws DBException {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE scores (id int, age int, gpa double)");
        executeStatement(dbManager,
                "INSERT INTO scores(id, age, gpa) VALUES (1, 18, 3.6), (2, 20, 3.9), (3, 20, 3.2)");

        assertThat(selectValues(dbManager, "SELECT max(gpa) FROM scores").get(0)[0].value)
                .isEqualTo(3.9);
        assertThat(selectValues(dbManager, "SELECT min(age) FROM scores").get(0)[0].value)
                .isEqualTo(18L);

        List<Value[]> grouped = selectValues(dbManager, "SELECT age, max(gpa) FROM scores GROUP BY age");

        assertThat(grouped).hasSize(2);
        assertThat(grouped.get(0)[0].value).isEqualTo(18L);
        assertThat(grouped.get(0)[1].value).isEqualTo(3.6);
        assertThat(grouped.get(1)[0].value).isEqualTo(20L);
        assertThat(grouped.get(1)[1].value).isEqualTo(3.9);
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

    private List<Value[]> selectValues(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        ArrayList<Value[]> rows = new ArrayList<>();
        physicalOperator.Begin();
        while (physicalOperator.hasNext()) {
            physicalOperator.Next();
            Tuple tuple = physicalOperator.Current();
            rows.add(tuple.getValues());
        }
        physicalOperator.Close();
        return rows;
    }
}
