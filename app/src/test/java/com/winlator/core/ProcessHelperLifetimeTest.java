package com.winlator.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Finite worker tasks and launch-time logging ownership without executing the guest runtime. */
public final class ProcessHelperLifetimeTest {
    @Before public void startRegistry() { ProcessHelper.removeAllDebugCallbacks(); }
    @After public void finishRegistry() { ProcessHelper.removeAllDebugCallbacks(); }

    @Test public void laterListenersCannotInheritAnAlreadyRunningProcessesOutput() {
        List<String> original = new ArrayList<>(), later = new ArrayList<>();
        ProcessHelper.addDebugCallback(original::add);
        ProcessHelper.DebugScope captured = ProcessHelper.captureDebugScope();
        ProcessHelper.addDebugCallback(later::add);
        ProcessHelper.deliverDebug(captured, "old-output");
        assertEquals(Arrays.asList("old-output"), original);
        assertTrue(later.isEmpty());
    }

    @Test public void removingTheRegistryRejectsOldOutputEvenWhenTheSameListenerIsReadded() {
        List<String> messages = new ArrayList<>();
        Callback<String> callback = messages::add;
        ProcessHelper.addDebugCallback(callback);
        ProcessHelper.DebugScope old = ProcessHelper.captureDebugScope();
        ProcessHelper.removeAllDebugCallbacks();
        ProcessHelper.addDebugCallback(callback);
        ProcessHelper.deliverDebug(old, "stale");
        assertTrue(messages.isEmpty());
        ProcessHelper.deliverDebug(ProcessHelper.captureDebugScope(), "current");
        assertEquals(Arrays.asList("current"), messages);
    }

    @Test public void removedIndividualListenerReceivesNoFurtherCapturedOutput() {
        List<String> removed = new ArrayList<>(), retained = new ArrayList<>();
        Callback<String> first = removed::add;
        ProcessHelper.addDebugCallback(first); ProcessHelper.addDebugCallback(retained::add);
        ProcessHelper.DebugScope captured = ProcessHelper.captureDebugScope();
        ProcessHelper.removeDebugCallback(first);
        ProcessHelper.deliverDebug(captured, "active");
        assertTrue(removed.isEmpty());
        assertEquals(Arrays.asList("active"), retained);
    }

    @Test public void resetFromACallbackStopsTheRestOfTheOldGenerationDelivery() {
        List<String> messages = new ArrayList<>();
        Callback<String> retained = messages::add;
        ProcessHelper.addDebugCallback(line -> {
            ProcessHelper.removeAllDebugCallbacks();
            ProcessHelper.addDebugCallback(retained);
        });
        ProcessHelper.addDebugCallback(retained);
        ProcessHelper.deliverDebug(ProcessHelper.captureDebugScope(), "stale");
        assertTrue(messages.isEmpty());
    }

    @Test(timeout = 4000) public void outputWorkerClosesAndEndsAtEofInsteadOfLeavingAnIdleExecutor() throws Exception {
        List<String> messages = new ArrayList<>();
        ProcessHelper.addDebugCallback(messages::add);
        ClosedInput input = new ClosedInput("one\ntwo\n");
        Thread worker = ProcessHelper.createDebugThread(input, ProcessHelper.captureDebugScope());
        assertTrue(worker.isDaemon()); assertEquals("pd2-runtime-output", worker.getName());
        worker.join(2000);
        assertFalse(worker.isAlive()); assertTrue(input.closed);
        assertEquals(Arrays.asList("one", "two"), messages);
    }

    @Test(timeout = 4000) public void oldBlockedStreamIsDrainedWithoutWritingToTheReplacementAttempt() throws Exception {
        List<String> old = new ArrayList<>(), replacement = new ArrayList<>();
        ProcessHelper.addDebugCallback(old::add);
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream output = new PipedOutputStream(input);
        Thread worker = ProcessHelper.createDebugThread(input, ProcessHelper.captureDebugScope());
        ProcessHelper.removeAllDebugCallbacks();
        ProcessHelper.addDebugCallback(replacement::add);
        output.write("late-wine-stderr\n".getBytes(StandardCharsets.UTF_8)); output.close();
        worker.join(2000);
        assertFalse(worker.isAlive()); assertTrue(old.isEmpty()); assertTrue(replacement.isEmpty());
        Thread current = ProcessHelper.createDebugThread(new ClosedInput("new-attempt\n"), ProcessHelper.captureDebugScope());
        current.join(2000);
        assertEquals(Arrays.asList("new-attempt"), replacement);
    }

    @Test(timeout = 4000) public void failedDiagnosticSinkCannotKillTheReaderOrStarveOtherListeners() throws Exception {
        List<String> messages = new ArrayList<>();
        ProcessHelper.addDebugCallback(line -> { throw new IllegalStateException("bad sink"); });
        ProcessHelper.addDebugCallback(messages::add);
        Thread worker = ProcessHelper.createDebugThread(new ClosedInput("one\ntwo\n"), ProcessHelper.captureDebugScope());
        worker.join(2000);
        assertFalse(worker.isAlive()); assertEquals(Arrays.asList("one", "two"), messages);
    }

    @Test(timeout = 4000) public void outputReadFailureStillClosesTheStreamAndTerminatesTheDaemon() throws Exception {
        boolean[] closed = {false};
        InputStream input = new InputStream() {
            public int read() throws IOException { throw new IOException("closed child"); }
            public void close() { closed[0] = true; }
        };
        Thread worker = ProcessHelper.createDebugThread(input, ProcessHelper.captureDebugScope());
        worker.join(2000);
        assertFalse(worker.isAlive()); assertTrue(closed[0]);
    }

    @Test(timeout = 4000) public void waitWorkerDeliversOnceAndEndsWithoutAnIdleExecutor() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        Thread worker = ProcessHelper.createWaitForThread(new FinishedProcess(7), statuses::add, ProcessHelper.captureDebugScope());
        assertTrue(worker.isDaemon()); assertEquals("pd2-runtime-wait", worker.getName());
        worker.join(2000);
        assertFalse(worker.isAlive()); assertEquals(Arrays.asList(7), statuses);
    }

    @Test(timeout = 4000) public void failedTerminationCallbackIsBoundedToItsOriginalDiagnosticGeneration() throws Exception {
        List<String> active = new ArrayList<>(), replacement = new ArrayList<>();
        ProcessHelper.addDebugCallback(active::add);
        ProcessHelper.DebugScope original = ProcessHelper.captureDebugScope();
        Thread first = ProcessHelper.createWaitForThread(new FinishedProcess(0), status -> {
            throw new IllegalStateException("no sensitive message export");
        }, original);
        first.join(2000);
        assertFalse(first.isAlive());
        assertEquals(Arrays.asList("Runtime termination callback failed: IllegalStateException"), active);
        ProcessHelper.removeAllDebugCallbacks(); ProcessHelper.addDebugCallback(replacement::add);
        Thread stale = ProcessHelper.createWaitForThread(new FinishedProcess(0), status -> { throw new IllegalStateException(); }, original);
        stale.join(2000);
        assertFalse(stale.isAlive()); assertTrue(replacement.isEmpty());
    }

    private static final class ClosedInput extends ByteArrayInputStream {
        boolean closed;
        ClosedInput(String input) { super(input.getBytes(StandardCharsets.UTF_8)); }
        public void close() throws IOException { closed = true; super.close(); }
    }

    private static final class FinishedProcess extends java.lang.Process {
        final int status;
        FinishedProcess(int status) { this.status = status; }
        public OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() { return status; }
        public int exitValue() { return status; }
        public void destroy() { }
    }
}
