package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlterTableTest {

    @TempDir
    Path tempDir;

    @Test
    void addColumnRewritesRowsAndPersistsDefaultValues() throws Exception {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE users (id int, age int)");
        executeStatement(dbManager, "INSERT INTO users(id, age) VALUES (1, 18), (2, 20)");

        executeStatement(dbManager, "ALTER TABLE users ADD COLUMN name varchar");
        executeStatement(dbManager, "UPDATE users SET name = 'alice' WHERE id = 2");

        assertThat(dbManager.getMetaManager().getTable("users").columns_list)
                .extracting(column -> column.name)
                .containsExactly("id", "age", "name");
        List<Value[]> rows = selectValues(dbManager, "SELECT * FROM users ORDER BY id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0].value).isEqualTo(1L);
        assertThat(rows.get(0)[1].value).isEqualTo(18L);
        assertThat(rows.get(0)[2].value).isEqualTo("");
        assertThat(rows.get(1)[2].value).isEqualTo("alice");

        dbManager.persistRuntimeState();
        DBManager restarted = buildDbManagerFromDisk();
        List<Value[]> restartedRows = selectValues(restarted, "SELECT * FROM users ORDER BY id");
        assertThat(restartedRows.get(1)[2].value).isEqualTo("alice");
    }

    @Test
    void dropColumnRewritesRowsAndDropsDependentIndex() throws Exception {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE users (id int, age int, name varchar)");
        executeStatement(dbManager, "INSERT INTO users(id, age, name) VALUES (1, 18, 'a'), (2, 20, 'b')");
        executeStatement(dbManager, "CREATE INDEX idx_age ON users(age)");

        executeStatement(dbManager, "ALTER TABLE users DROP COLUMN age");

        assertThat(dbManager.getMetaManager().getTable("users").columns_list)
                .extracting(column -> column.name)
                .containsExactly("id", "name");
        assertThat(dbManager.getMetaManager().getTable("users").getIndexes())
                .doesNotContainKey("idx_age");
        assertThat(dbManager.getMetaManager().getTable("users").getIndexColumns())
                .doesNotContainKey("idx_age");
        List<Value[]> rows = selectValues(dbManager, "SELECT * FROM users ORDER BY id");
        assertThat(rows.get(0)[0].value).isEqualTo(1L);
        assertThat(rows.get(0)[1].value).isEqualTo("a");
        assertThat(rows.get(1)[0].value).isEqualTo(2L);
        assertThat(rows.get(1)[1].value).isEqualTo("b");
    }

    private DBManager buildDbManager() throws DBException {
        DiskManager diskManager = new DiskManager(tempDir.toString(), new HashMap<>());
        BufferPool bufferPool = new BufferPool(16, diskManager);
        RecordManager recordManager = new RecordManager(diskManager, bufferPool);
        MetaManager metaManager = new MetaManager(tempDir.resolve("meta").toString());
        return new DBManager(diskManager, bufferPool, recordManager, metaManager);
    }

    private DBManager buildDbManagerFromDisk() throws DBException {
        DiskManager diskManager = new DiskManager(tempDir.toString(),
                new HashMap<>(DiskManager.read_disk_manager_meta(tempDir.toString())));
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
        var physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
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
        var physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        ArrayList<Value[]> rows = new ArrayList<>();
        physicalOperator.Begin();
        while (physicalOperator.hasNext()) {
            physicalOperator.Next();
            rows.add(physicalOperator.Current().getValues());
        }
        physicalOperator.Close();
        return rows;
    }
}
