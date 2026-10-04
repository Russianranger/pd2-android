package com.winlator.pd2;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.Callback;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Removes only an explicitly selected, unused private Wine container. */
public final class Pd2ContainerMaintenance {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean deleting;
    private static int containerWorkers;

    private Pd2ContainerMaintenance() {}

    public static boolean isDeletionInProgress() { return deleting; }
    public static synchronized boolean isContainerWorkInProgress() { return containerWorkers != 0; }

    public static synchronized boolean beginContainerWork() {
        if (deleting) return false;
        containerWorkers++;
        return true;
    }

    public static synchronized void endContainerWork() { if (containerWorkers > 0) containerWorkers--; }

    private static boolean runtimeBusy() {
        return isContainerWorkInProgress() || Pd2Activity.isOperationInProgress() || XServerDisplayActivity.isPd2RuntimeWorkInProgress();
    }

    /** Null means eligible now; the worker repeats this check immediately before removal. */
    public static String deletionBlockReason(Context context, Container container) {
        if (deleting) return "Another container deletion is still finishing.";
        return check(context, container == null ? 0 : container.id, Pd2ContainerMaintenance::runtimeBusy);
    }

    public static void deleteAsync(Context context, Container container, Callback<String> callback) {
        Context app = context.getApplicationContext();
        final Ticket ticket;
        synchronized (Pd2ContainerMaintenance.class) {
            String reason = deletionBlockReason(app, container);
            if (reason != null) { callback.call(reason); return; }
            try { ticket = new Ticket(container); }
            catch (IOException error) { callback.call("Cannot verify this container. Refresh the list and retry."); return; }
            // Launcher/runtime entry points check this before preparing or starting a session.
            deleting = true;
        }
        WORKER.execute(() -> {
            String result;
            try { result = deleteNow(app, ticket, Pd2ContainerMaintenance::runtimeBusy); }
            catch (RuntimeException error) { result = "Cannot delete this container. Refresh the list and retry."; }
            finally { synchronized (Pd2ContainerMaintenance.class) { deleting = false; } }
            final String message = result;
            Pd2Activity.appendLauncherLog(app, "Container deletion #" + ticket.id + ": "
                    + (message == null ? "completed" : message));
            MAIN.post(() -> callback.call(message));
        });
    }

    static final class Ticket {
        final int id;
        final Path root;
        final Object directoryIdentity, configIdentity;
        Ticket(Container container) throws IOException {
            id = container.id;
            root = container.getRootDir().toPath().toAbsolutePath().normalize();
            directoryIdentity = identity(root);
            configIdentity = identity(root.resolve(".container"));
        }
    }

    static String deleteNow(Context context, Ticket ticket, BooleanSupplier busy) {
        String reason = check(context, ticket.id, busy);
        if (reason != null) return reason;
        try {
            Container fresh = new ContainerManager(context).getContainerById(ticket.id);
            if (fresh == null || !fresh.getRootDir().toPath().toAbsolutePath().normalize().equals(ticket.root)
                    || !Objects.equals(ticket.directoryIdentity, identity(ticket.root))
                    || !Objects.equals(ticket.configIdentity, identity(ticket.root.resolve(".container"))))
                return "This container changed. Refresh the list before deleting it.";
            // No FOLLOW_LINKS: mapped drives, user-folder links and broken links are unlinked only.
            Files.walkFileTree(ticket.root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
            return null;
        }
        catch (IOException | RuntimeException error) {
            return "Container deletion did not finish. Refresh the list and check free storage before retrying.";
        }
    }

    static String check(Context context, int id, BooleanSupplier busy) {
        if (busy.getAsBoolean()) return "Stop the client and wait for runtime work to finish before deleting a container.";
        if (id <= 0) return "Cannot identify this container.";
        try {
            File rootfs = RootFS.find(context).getRootDir();
            File home = new File(rootfs, "home");
            File expected = new File(home, RootFS.USER + "-" + id);
            // A linked ancestor or target could turn a prefix removal into deletion of shared data.
            if (Files.isSymbolicLink(rootfs.toPath()) || Files.isSymbolicLink(home.toPath())
                    || Files.isSymbolicLink(expected.toPath()) || !expected.isDirectory()
                    || !expected.getCanonicalFile().getParentFile().equals(home.getCanonicalFile()))
                return "This container path is not a private removable directory.";
            File shared = new File(context.getFilesDir(), "pd2").getCanonicalFile();
            if (shared.toPath().startsWith(expected.getCanonicalFile().toPath()))
                return "This container contains protected PD2 files.";
            ContainerManager manager = new ContainerManager(context);
            Container target = manager.getContainerById(id);
            if (target == null) return "Cannot read this container safely. Its files have been kept.";
            Container current = Pd2Runtime.findCurrentContainer(context, manager);
            if (current == null) return "Cannot identify the current PD2 container. Existing containers have been kept.";
            if (current.id == id || Pd2Runtime.savedContainerId(context) == id)
                return "The current PD2 container is protected.";
            File active = new File(home, RootFS.USER);
            if (active.getCanonicalFile().equals(expected.getCanonicalFile()))
                return "This container is selected by the runtime and is protected.";
            // Repeat after disk inspection: a queued request must not cross a new startup/cleanup.
            if (busy.getAsBoolean()) return "Runtime work started. The container has been kept.";
            return null;
        }
        catch (IOException | RuntimeException error) {
            return "Cannot verify this container safely. Its files have been kept.";
        }
    }

    private static Object identity(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }
}
