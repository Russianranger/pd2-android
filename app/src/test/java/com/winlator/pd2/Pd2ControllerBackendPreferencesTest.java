package com.winlator.pd2;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Executes the production preference migration without an Activity or Wine runtime. */
public final class Pd2ControllerBackendPreferencesTest {
    @Test public void freshPreferencesAdoptTheAcceptedReaderDefault() {
        MemoryPreferences preferences = new MemoryPreferences();
        assertEquals(Pd2ControllerBackendPolicy.XINPUT_READER_REPAIR, Pd2ControllerBackendPolicy.DEFAULT);
        assertEquals(Pd2ControllerBackendPolicy.DEFAULT, Pd2ControllerBackendPreferences.get(preferences));
        assertTrue(preferences.getBoolean(Pd2ControllerBackendPreferences.MIGRATION_PREFERENCE, false));
        assertEquals(Pd2ControllerBackendPolicy.DEFAULT,
                preferences.getInt(Pd2ControllerBackendPolicy.PREFERENCE, -1));
        assertEquals(1, preferences.edits);
    }

    @Test public void eachLegacyChoiceMigratesOnceWithoutChangingOtherPreferences() {
        for (int old : new int[]{Pd2ControllerBackendPolicy.STANDARD,
                Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY, Pd2ControllerBackendPolicy.XINPUT_READER_REPAIR, -4}) {
            MemoryPreferences preferences = new MemoryPreferences();
            preferences.values.put(Pd2ControllerBackendPolicy.PREFERENCE, old);
            preferences.values.put("pd2_renderer", "turnip,zink");
            preferences.values.put("pd2_arguments", "-3dfx -dxnocompatmodefix");
            preferences.values.put("pd2_input_trace", true);
            preferences.values.put("controller_notifications", false);
            Map<String, ?> before = preferences.getAll();
            assertEquals(Pd2ControllerBackendPolicy.DEFAULT, Pd2ControllerBackendPreferences.get(preferences));
            assertEquals(Pd2ControllerBackendPolicy.DEFAULT, Pd2ControllerBackendPreferences.get(preferences));
            Map<String, ?> after = preferences.getAll();
            before.remove(Pd2ControllerBackendPolicy.PREFERENCE);
            after.remove(Pd2ControllerBackendPolicy.PREFERENCE);
            after.remove(Pd2ControllerBackendPreferences.MIGRATION_PREFERENCE);
            assertEquals(before, after);
            assertEquals("Repeated reads must not repeat the migration", 1, preferences.edits);
        }
    }

    @Test public void aManualFallbackSurvivesSubsequentReadsAndReload() {
        for (int selected : new int[]{Pd2ControllerBackendPolicy.STANDARD,
                Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY, Pd2ControllerBackendPolicy.XINPUT_READER_REPAIR}) {
            MemoryPreferences preferences = new MemoryPreferences();
            Pd2ControllerBackendPreferences.get(preferences);
            Pd2ControllerBackendPreferences.set(preferences, selected);
            assertEquals(selected, Pd2ControllerBackendPreferences.get(preferences));
            MemoryPreferences reloaded = new MemoryPreferences(preferences.getAll());
            assertEquals(selected, Pd2ControllerBackendPreferences.get(reloaded));
            assertEquals(0, reloaded.edits);
        }
    }

    @Test public void anExplicitChoiceBeforeTheFirstReadAlsoCompletesMigration() {
        MemoryPreferences preferences = new MemoryPreferences();
        Pd2ControllerBackendPreferences.set(preferences, Pd2ControllerBackendPolicy.STANDARD);
        assertEquals(Pd2ControllerBackendPolicy.STANDARD, Pd2ControllerBackendPreferences.get(preferences));
        assertEquals(1, preferences.edits);
    }

    @Test public void absentModeAfterMigrationUsesDefaultWithoutChangingOtherSettings() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.values.put(Pd2ControllerBackendPreferences.MIGRATION_PREFERENCE, true);
        assertEquals(Pd2ControllerBackendPolicy.DEFAULT, Pd2ControllerBackendPreferences.get(preferences));
        assertEquals(0, preferences.edits);
    }

    @Test public void malformedStoredTypesCannotCrashLaunchPolicy() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.values.put(Pd2ControllerBackendPreferences.MIGRATION_PREFERENCE, "old-format");
        preferences.values.put(Pd2ControllerBackendPolicy.PREFERENCE, "unknown");
        assertEquals(Pd2ControllerBackendPolicy.DEFAULT, Pd2ControllerBackendPreferences.get(preferences));
        preferences.values.put(Pd2ControllerBackendPolicy.PREFERENCE, "unknown");
        assertEquals(Pd2ControllerBackendPolicy.DEFAULT, Pd2ControllerBackendPreferences.get(preferences));
        assertEquals(1, preferences.edits);
    }

    @Test public void migrationWritesTheChoiceAndMarkerTogether() {
        MemoryPreferences preferences = new MemoryPreferences();
        Pd2ControllerBackendPreferences.get(preferences);
        assertEquals(2, preferences.lastEdit.size());
        assertEquals(Boolean.TRUE, preferences.lastEdit.get(Pd2ControllerBackendPreferences.MIGRATION_PREFERENCE));
        assertEquals(Pd2ControllerBackendPolicy.DEFAULT,
                preferences.lastEdit.get(Pd2ControllerBackendPolicy.PREFERENCE));
    }

    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        Map<String, Object> lastEdit;
        int edits;
        MemoryPreferences() {}
        MemoryPreferences(Map<String, ?> saved) { values.putAll(saved); }
        public Map<String, ?> getAll() { return new HashMap<>(values); }
        public String getString(String key, String fallback) {
            return values.containsKey(key) ? (String) values.get(key) : fallback;
        }
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key, Set<String> fallback) {
            return values.containsKey(key) ? (Set<String>) values.get(key) : fallback;
        }
        public int getInt(String key, int fallback) {
            return values.containsKey(key) ? (Integer) values.get(key) : fallback;
        }
        public long getLong(String key, long fallback) {
            return values.containsKey(key) ? (Long) values.get(key) : fallback;
        }
        public float getFloat(String key, float fallback) {
            return values.containsKey(key) ? (Float) values.get(key) : fallback;
        }
        public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }
        public boolean contains(String key) { return values.containsKey(key); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> pending = new HashMap<>();
                final Set<String> removed = new HashSet<>();
                boolean clear;
                public Editor putString(String key, String value) { pending.put(key, value); return this; }
                public Editor putStringSet(String key, Set<String> value) { pending.put(key, value); return this; }
                public Editor putInt(String key, int value) { pending.put(key, value); return this; }
                public Editor putLong(String key, long value) { pending.put(key, value); return this; }
                public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
                public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
                public Editor remove(String key) { removed.add(key); return this; }
                public Editor clear() { clear = true; return this; }
                public boolean commit() {
                    if (clear) values.clear();
                    for (String key : removed) values.remove(key);
                    values.putAll(pending);
                    lastEdit = new HashMap<>(pending);
                    edits++;
                    return true;
                }
                public void apply() { commit(); }
            };
        }
    }
}
