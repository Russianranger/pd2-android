package com.winlator.xenvironment;

import android.app.Application;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class XEnvironmentTest {
    private static Thread.UncaughtExceptionHandler hostHandler;
    @BeforeClass public static void preserveExceptionHandler() { hostHandler = Thread.getDefaultUncaughtExceptionHandler(); }
    @After public void restoreExceptionHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }

    @Test public void socketFailureNamesTheComponentAndPreventsGuestExecution() {
        Application application = RuntimeEnvironment.getApplication();
        XEnvironment environment = new XEnvironment(application, RootFS.find(application));
        List<String> events = new ArrayList<>();
        RecordingComponent first = new RecordingComponent(events, "display");
        RuntimeException cause = new RuntimeException("Cannot allocate connector for /tmp/.sysvshm/SM0");
        BrokenSocketComponent broken = new BrokenSocketComponent(events, cause);
        RecordingComponent guest = new RecordingComponent(events, "guest");
        environment.addComponent(first);
        environment.addComponent(broken);
        environment.addComponent(guest);

        IllegalStateException failure = assertThrows(IllegalStateException.class, environment::startEnvironmentComponents);
        assertSame(cause, failure.getCause());
        assertTrue(failure.getMessage().contains("BrokenSocketComponent"));
        assertTrue(failure.getMessage().contains("/tmp/.sysvshm/SM0"));
        assertEquals(Arrays.asList("start display", "start socket"), events);
        // The existing teardown can still clean partially started components after a failure.
        environment.stopEnvironmentComponents();
        assertEquals(Arrays.asList("start display", "start socket", "stop display", "stop socket", "stop guest"), events);
    }

    @Test public void successfulStartupKeepsTheGuestAfterServices() {
        Application application = RuntimeEnvironment.getApplication();
        XEnvironment environment = new XEnvironment(application, RootFS.find(application));
        List<String> events = new ArrayList<>();
        environment.addComponent(new RecordingComponent(events, "shared memory"));
        environment.addComponent(new RecordingComponent(events, "display"));
        environment.addComponent(new RecordingComponent(events, "audio"));
        environment.addComponent(new RecordingComponent(events, "guest"));
        environment.startEnvironmentComponents();
        assertEquals(Arrays.asList("start shared memory", "start display", "start audio", "start guest"), events);
    }

    private static final class RecordingComponent extends EnvironmentComponent {
        private final List<String> events;
        private final String name;
        RecordingComponent(List<String> events, String name) { this.events = events; this.name = name; }
        @Override public void start() { events.add("start " + name); }
        @Override public void stop() { events.add("stop " + name); }
    }

    private static final class BrokenSocketComponent extends EnvironmentComponent {
        private final List<String> events;
        private final RuntimeException cause;
        BrokenSocketComponent(List<String> events, RuntimeException cause) { this.events = events; this.cause = cause; }
        @Override public void start() { events.add("start socket"); throw cause; }
        @Override public void stop() { events.add("stop socket"); }
    }
}
