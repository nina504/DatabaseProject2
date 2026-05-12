package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.IndexScanOperator;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexDDLTest {

    @TempDir
    Path tempDir;

    @Test
    void createIndexPersistsMetadataAndDoesNotBreakSelect() throws Exception {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE users (id int, age int)");
        executeStatement(dbManager, "INSERT INTO users(id, age) VALUES (1, 18), (2, 20), (3, 21)");

        executeStatement(dbManager, "CREATE INDEX idx_age ON users(age)");

        assertThat(dbManager.getMetaManager().getTable("users").getIndexes())
                .containsKey("idx_age");
        assertThat(dbManager.getMetaManager().getTable("users").getIndexColumns())
                .containsEntry("idx_age", "age");
        assertThat(containsOperator(buildPhysicalOperator(dbManager, "SELECT * FROM users WHERE age = 20"),
                IndexScanOperator.class)).isTrue();
        assertThat(selectValues(dbManager, "SELECT * FROM users WHERE age = 20").get(0)[0].value)
                .isEqualTo(2L);

        executeStatement(dbManager, "DROP INDEX idx_age");

        assertThat(dbManager.getMetaManager().getTable("users").getIndexes())
                .doesNotContainKey("idx_age");
        assertThat(dbManager.getMetaManager().getTable("users").getIndexColumns())
                .doesNotContainKey("idx_age");
    }

    @Test
    void indexScanReflectsInsertDeleteAndUpdate() throws DBException {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE users (id int, age int)");
        executeStatement(dbManager, "CREATE INDEX idx_age ON users(age)");

        executeStatement(dbManager, "INSERT INTO users(id, age) VALUES (1, 18), (2, 20)");
        assertThat(selectValues(dbManager, "SELECT * FROM users WHERE age = 20").get(0)[0].value)
                .isEqualTo(2L);

        executeStatement(dbManager, "UPDATE users SET age = 21 WHERE id = 2");
        assertThat(selectValues(dbManager, "SELECT * FROM users WHERE age = 20"))
                .isEmpty();
        assertThat(selectValues(dbManager, "SELECT * FROM users WHERE age = 21").get(0)[0].value)
                .isEqualTo(2L);

        executeStatement(dbManager, "DELETE FROM users WHERE id = 2");
        assertThat(selectValues(dbManager, "SELECT * FROM users WHERE age = 21"))
                .isEmpty();
    }

    @Test
    void indexScanRebuildsFromPersistedMetadataAfterRestart() throws Exception {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE users (id int, age int)");
        executeStatement(dbManager, "INSERT INTO users(id, age) VALUES (1, 18), (2, 20)");
        executeStatement(dbManager, "CREATE INDEX idx_age ON users(age)");
        dbManager.persistRuntimeState();

        DBManager restarted = buildDbManagerFromDisk();

        assertThat(containsOperator(buildPhysicalOperator(restarted, "SELECT * FROM users WHERE age = 20"),
                IndexScanOperator.class)).isTrue();
        assertThat(selectValues(restarted, "SELECT * FROM users WHERE age = 20").get(0)[0].value)
                .isEqualTo(2L);
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
        PhysicalOperator physicalOperator = buildPhysicalOperator(dbManager, sql);
        ArrayList<Value[]> rows = new ArrayList<>();
        physicalOperator.Begin();
        while (physicalOperator.hasNext()) {
            physicalOperator.Next();
            rows.add(physicalOperator.Current().getValues());
        }
        physicalOperator.Close();
        return rows;
    }

    private PhysicalOperator buildPhysicalOperator(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        return PhysicalPlanner.generateOperator(dbManager, logicalOperator);
    }

    private boolean containsOperator(PhysicalOperator operator, Class<?> targetType) throws IllegalAccessException {
        if (targetType.isInstance(operator)) {
            return true;
        }
        for (Field field : operator.getClass().getDeclaredFields()) {
            if (!PhysicalOperator.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            PhysicalOperator child = (PhysicalOperator) field.get(operator);
            if (child != null && containsOperator(child, targetType)) {
                return true;
            }
        }
        return false;
    }
}
