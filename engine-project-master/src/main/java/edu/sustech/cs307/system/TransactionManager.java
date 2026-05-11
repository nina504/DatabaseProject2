package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.storage.DiskManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;


public class TransactionManager {

    private final DBManager dbManager;
    private Path transactionSnapshot;
    private boolean active;
    private final List<Savepoint> savepoints;

    private static class Savepoint {
        final String name;
        final Path snapshot;

        Savepoint(String name, Path snapshot) {
            this.name = name;
            this.snapshot = snapshot;
        }
    }


    public TransactionManager(DBManager dbManager) {
        this.dbManager = dbManager;
        this.active = false;
        this.savepoints = new ArrayList<>();
    }


    public void begin() throws DBException {
        if (active) {
            throw new DBException(ExceptionTypes.TransactionAlreadyActive());
        }
        transactionSnapshot = createSnapshot();
        savepoints.clear();
        active = true;
    }


    public void commit() throws DBException {
        if (!active) {
            return;
        }
        dbManager.persistRuntimeState();
        cleanupSnapshot(transactionSnapshot);
        for (Savepoint savepoint : savepoints) {
            cleanupSnapshot(savepoint.snapshot);
        }
        transactionSnapshot = null;
        savepoints.clear();
        active = false;
    }


    public void rollback() throws DBException {
        if (!active) {
            return;
        }
        restoreSnapshot(transactionSnapshot);
        for (Savepoint savepoint : savepoints) {
            cleanupSnapshot(savepoint.snapshot);
        }
        cleanupSnapshot(transactionSnapshot);
        transactionSnapshot = null;
        savepoints.clear();
        active = false;
    }


    public void savepoint(String savepointName) throws DBException {
        requireActiveTransaction();
        savepoints.add(new Savepoint(savepointName, createSnapshot()));
    }


    public void rollbackToSavepoint(String savepointName) throws DBException {
        requireActiveTransaction();
        int index = findSavepoint(savepointName);
        if (index < 0) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        Savepoint target = savepoints.get(index);
        restoreSnapshot(target.snapshot);
        for (int i = savepoints.size() - 1; i > index; i--) {
            cleanupSnapshot(savepoints.remove(i).snapshot);
        }
    }


    public void releaseSavepoint(String savepointName) throws DBException {
        requireActiveTransaction();
        int index = findSavepoint(savepointName);
        if (index < 0) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        cleanupSnapshot(savepoints.remove(index).snapshot);
    }

    private Path createSnapshot() throws DBException {
        dbManager.persistRuntimeState();
        Path snapshotDir;
        try {
            snapshotDir = Files.createTempDirectory("cs307-txn-");
            copyDirectoryContents(getDbRoot(), snapshotDir);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
        return snapshotDir;
    }

    private Path getDbRoot() {
        return Path.of(dbManager.getDiskManager().getCurrentDir());
    }

    private void requireActiveTransaction() throws DBException {
        if (!active) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }
    }

    private int findSavepoint(String savepointName) {
        for (int i = savepoints.size() - 1; i >= 0; i--) {
            if (savepoints.get(i).name.equals(savepointName)) {
                return i;
            }
        }
        return -1;
    }

    private void restoreSnapshot(Path snapshot) throws DBException {
        Path dbRoot = getDbRoot();
        try {
            dbManager.getBufferPool().Reset();
            deleteDirectoryContents(dbRoot);
            copyDirectoryContents(snapshot, dbRoot);
            dbManager.getDiskManager().filePages =
                    DiskManager.read_disk_manager_meta(dbRoot.toString());
            dbManager.getBufferPool().Reset();
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }

    private void copyDirectoryContents(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.exists(sourceRoot)) {
            Files.createDirectories(targetRoot);
            return;
        }
        Files.createDirectories(targetRoot);
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void deleteDirectoryContents(Path root) throws IOException {
        if (!Files.exists(root)) {
            Files.createDirectories(root);
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void cleanupSnapshot(Path snapshot) throws DBException {
        if (snapshot == null || !Files.exists(snapshot)) {
            return;
        }
        try (var paths = Files.walk(snapshot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }
}
