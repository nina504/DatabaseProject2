package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DBManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void dropTableClearsCachedPagesBeforeLaterFlushes() throws DBException {
        DiskManager diskManager = new DiskManager(tempDir.toString(), new HashMap<>());
        BufferPool bufferPool = new BufferPool(8, diskManager);
        RecordManager recordManager = new RecordManager(diskManager, bufferPool);
        MetaManager metaManager = new MetaManager(tempDir.resolve("meta").toString());
        DBManager dbManager = new DBManager(diskManager, bufferPool, recordManager, metaManager);

        ArrayList<ColumnMeta> columns = new ArrayList<>();
        columns.add(new ColumnMeta("users", "id", ValueType.INTEGER, 4, 0));

        dbManager.createTable("users", columns);
        RecordFileHandle handle = recordManager.OpenFile("users");
        handle.InsertRecord(Unpooled.buffer(4).writeInt(1));

        dbManager.dropTable("users");

        assertThatCode(() -> bufferPool.FlushAllPages("")).doesNotThrowAnyException();
        assertThat(Files.exists(tempDir.resolve("users").resolve("data"))).isFalse();
        assertThat(diskManager.filePages).doesNotContainKey("users/data");
        assertThatThrownBy(() -> metaManager.getTable("users")).isInstanceOf(DBException.class);
    }
}
