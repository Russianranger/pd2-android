package com.winlator.pd2;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2ThermalDiagnosticsTest {
    private static final class BatteryContext extends ContextWrapper {
        Intent battery;
        boolean failBattery, failPower;
        int queries;
        BatteryContext() { super(RuntimeEnvironment.getApplication()); }
        @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            assertNull("Telemetry must not leave a receiver registered", receiver);
            assertTrue(filter.hasAction(Intent.ACTION_BATTERY_CHANGED));
            queries++;
            if (failBattery) throw new SecurityException("Battery service unavailable");
            return battery;
        }
        @Override public Object getSystemService(String name) {
            if (POWER_SERVICE.equals(name) && failPower) throw new IllegalStateException("Thermal service unavailable");
            return super.getSystemService(name);
        }
    }

    @Test public void batteryTenthsAndAndroidThrottlingAreReportedWithDistinctLabels() throws Exception {
        BatteryContext context = new BatteryContext();
        context.battery = new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_TEMPERATURE, 357);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE);
        JSONObject sample = Pd2ThermalDiagnostics.collect(context);
        assertEquals(35.7, sample.getDouble("batteryTemperatureCelsius"), 0.00001);
        assertEquals("available", sample.getString("batteryTemperatureStatus"));
        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, sample.getInt("androidThermalStatus"));
        assertEquals("severe", sample.getString("androidThermalStatusName"));
        assertTrue(sample.getString("scope").contains("not CPU/GPU temperature"));
        assertEquals(1, context.queries);
    }

    @Test public void missingTemperatureIsUnknownWhileARealZeroIsPreserved() throws Exception {
        BatteryContext context = new BatteryContext();
        assertTrue(Pd2ThermalDiagnostics.collect(context).isNull("batteryTemperatureCelsius"));
        context.battery = new Intent(Intent.ACTION_BATTERY_CHANGED);
        assertTrue(Pd2ThermalDiagnostics.collect(context).isNull("batteryTemperatureCelsius"));
        context.battery.putExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        assertEquals(0, Pd2ThermalDiagnostics.collect(context).getDouble("batteryTemperatureCelsius"), 0);
    }

    @Test public void optionalServiceFailuresDoNotDropTheOtherMeasurement() throws Exception {
        BatteryContext context = new BatteryContext(); context.failBattery = true;
        JSONObject sample = Pd2ThermalDiagnostics.collect(context);
        assertTrue(sample.isNull("batteryTemperatureCelsius"));
        assertEquals("none", sample.getString("androidThermalStatusName"));
        context.failBattery = false; context.failPower = true;
        context.battery = new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_TEMPERATURE, 410);
        sample = Pd2ThermalDiagnostics.collect(context);
        assertEquals(41, sample.getDouble("batteryTemperatureCelsius"), 0);
        assertTrue(sample.isNull("androidThermalStatus"));
        assertEquals("unavailable", sample.getString("androidThermalStatusName"));
    }

    @Test @Config(sdk = 28) public void android9KeepsBatteryTemperatureWithoutCallingNewThermalApis() throws Exception {
        BatteryContext context = new BatteryContext(); context.failPower = true;
        context.battery = new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_TEMPERATURE, 382);
        JSONObject sample = Pd2ThermalDiagnostics.collect(context);
        assertEquals(38.2, sample.getDouble("batteryTemperatureCelsius"), 0.00001);
        assertTrue(sample.isNull("androidThermalStatus"));
        assertEquals("unsupported-api", sample.getString("androidThermalStatusName"));
    }
}
