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

    @Test
    void groupByUsesTypedKeysForIntFloatCharAndMultipleColumns() throws DBException {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE scores (id int, age int, gpa double, name varchar)");
        executeStatement(dbManager,
                "INSERT INTO scores(id, age, gpa, name) VALUES "
                        + "(1, 18, 3.6, 'alice'), "
                        + "(2, 18, 3.6, 'alice'), "
                        + "(3, 18, 3.9, 'alice'), "
                        + "(4, 20, 3.6, 'bob')");

        List<Value[]> byInt = selectValues(dbManager,
                "SELECT age, max(id) FROM scores GROUP BY age ORDER BY age");
        assertThat(byInt).hasSize(2);
        assertThat(byInt.get(0)[0].value).isEqualTo(18L);
        assertThat(byInt.get(0)[1].value).isEqualTo(3L);
        assertThat(byInt.get(1)[0].value).isEqualTo(20L);
        assertThat(byInt.get(1)[1].value).isEqualTo(4L);

        List<Value[]> byFloat = selectValues(dbManager,
                "SELECT gpa, max(id) FROM scores GROUP BY gpa ORDER BY gpa DESC");
        assertThat(byFloat).hasSize(2);
        assertThat(byFloat.get(0)[0].value).isEqualTo(3.9);
        assertThat(byFloat.get(0)[1].value).isEqualTo(3L);
        assertThat(byFloat.get(1)[0].value).isEqualTo(3.6);
        assertThat(byFloat.get(1)[1].value).isEqualTo(4L);

        List<Value[]> byChar = selectValues(dbManager,
                "SELECT name, max(id) FROM scores GROUP BY name ORDER BY name DESC");
        assertThat(byChar).hasSize(2);
        assertThat(byChar.get(0)[0].value).isEqualTo("bob");
        assertThat(byChar.get(0)[1].value).isEqualTo(4L);
        assertThat(byChar.get(1)[0].value).isEqualTo("alice");
        assertThat(byChar.get(1)[1].value).isEqualTo(3L);

        List<Value[]> byComposite = selectValues(dbManager,
                "SELECT name, gpa, max(id) FROM scores GROUP BY name, gpa ORDER BY name ASC, gpa DESC");
        assertThat(byComposite).hasSize(3);
        assertThat(byComposite.get(0)[0].value).isEqualTo("alice");
        assertThat(byComposite.get(0)[1].value).isEqualTo(3.9);
        assertThat(byComposite.get(0)[2].value).isEqualTo(3L);
        assertThat(byComposite.get(1)[0].value).isEqualTo("alice");
        assertThat(byComposite.get(1)[1].value).isEqualTo(3.6);
        assertThat(byComposite.get(1)[2].value).isEqualTo(2L);
        assertThat(byComposite.get(2)[0].value).isEqualTo("bob");
        assertThat(byComposite.get(2)[1].value).isEqualTo(3.6);
        assertThat(byComposite.get(2)[2].value).isEqualTo(4L);
    }

    @Test
    void orderBySupportsAscendingDescendingAndTieBreakers() throws DBException {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE scores (id int, age int, gpa double, name varchar)");
        executeStatement(dbManager,
                "INSERT INTO scores(id, age, gpa, name) VALUES "
                        + "(1, 18, 3.6, 'alice'), "
                        + "(2, 20, 3.9, 'bob'), "
                        + "(3, 20, 3.2, 'carol'), "
                        + "(4, 18, 3.9, 'dave')");

        List<Value[]> byIdDesc = selectValues(dbManager, "SELECT id FROM scores ORDER BY id DESC");
        assertThat(byIdDesc).extracting(row -> row[0].value)
                .containsExactly(4L, 3L, 2L, 1L);

        List<Value[]> byAgeAscIdDesc = selectValues(dbManager,
                "SELECT id, age FROM scores ORDER BY age ASC, id DESC");
        assertThat(byAgeAscIdDesc).extracting(row -> row[0].value)
                .containsExactly(4L, 1L, 3L, 2L);

        List<Value[]> byNameDesc = selectValues(dbManager,
                "SELECT name FROM scores ORDER BY name DESC");
        assertThat(byNameDesc).extracting(row -> row[0].value)
                .containsExactly("dave", "carol", "bob", "alice");
    }

    @Test
    void sumAvgCountAndExpressionsWorkInAggregatesAndProjection() throws DBException {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE scores (id int, age int, gpa double, name varchar)");
        executeStatement(dbManager,
                "INSERT INTO scores(id, age, gpa, name) VALUES "
                        + "(1, 18, 3.5, 'alice'), "
                        + "(2, 18, 4.0, 'bob'), "
                        + "(3, 20, 3.0, 'carol')");

        List<Value[]> aggregate = selectValues(dbManager,
                "SELECT sum(age), avg(gpa), max(age + id), min(gpa + 1.0) FROM scores");
        assertThat(aggregate.get(0)[0].value).isEqualTo(56L);
        assertThat(aggregate.get(0)[1].value).isEqualTo((3.5 + 4.0 + 3.0) / 3);
        assertThat(aggregate.get(0)[2].value).isEqualTo(23L);
        assertThat(aggregate.get(0)[3].value).isEqualTo(4.0);

        List<Value[]> grouped = selectValues(dbManager,
                "SELECT age, count(*), sum(id), avg(gpa) FROM scores GROUP BY age ORDER BY age DESC");
        assertThat(grouped).hasSize(2);
        assertThat(grouped.get(0)[0].value).isEqualTo(20L);
        assertThat(grouped.get(0)[1].value).isEqualTo(1L);
        assertThat(grouped.get(0)[2].value).isEqualTo(3L);
        assertThat(grouped.get(0)[3].value).isEqualTo(3.0);
        assertThat(grouped.get(1)[0].value).isEqualTo(18L);
        assertThat(grouped.get(1)[1].value).isEqualTo(2L);
        assertThat(grouped.get(1)[2].value).isEqualTo(3L);
        assertThat(grouped.get(1)[3].value).isEqualTo(3.75);

        List<Value[]> projected = selectValues(dbManager,
                "SELECT id + age, gpa * 2 FROM scores ORDER BY id + age DESC");
        assertThat(projected).extracting(row -> row[0].value)
                .containsExactly(23L, 20L, 19L);
        assertThat(projected).extracting(row -> row[1].value)
                .containsExactly(6.0, 8.0, 7.0);
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
