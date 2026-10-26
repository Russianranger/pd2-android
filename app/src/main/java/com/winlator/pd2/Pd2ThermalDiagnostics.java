package com.winlator.pd2;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only telemetry; battery temperature is not CPU or GPU temperature. */
final class Pd2ThermalDiagnostics {
    private Pd2ThermalDiagnostics() {}

    static JSONObject collect(Context context) {
        JSONObject report = new JSONObject();
        try {
            report.put("batteryTemperatureCelsius", JSONObject.NULL).put("batteryTemperatureStatus", "unavailable")
                    .put("androidThermalStatus", JSONObject.NULL)
                    .put("androidThermalStatusName", Build.VERSION.SDK_INT < 29 ? "unsupported-api" : "unavailable");
            try {
                // Query the cached sticky intent; this does not register a continuing receiver.
                Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery != null && battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                    report.put("batteryTemperatureCelsius", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
                            .put("batteryTemperatureStatus", "available");
                }
            } catch (RuntimeException ignored) { }
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (power != null) {
                        int status = Api29.status(power);
                        report.put("androidThermalStatus", status).put("androidThermalStatusName", statusName(status));
                    }
                } catch (RuntimeException | LinkageError ignored) { }
            }
            report.put("scope", "Battery sensor temperature and Android thermal throttling status; not CPU/GPU temperature.");
        } catch (JSONException ignored) { }
        return report;
    }

    private static String statusName(int status) {
        String[] names = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};
        return status >= 0 && status < names.length ? names[status] : "unknown";
    }

    private static final class Api29 {
        static int status(PowerManager power) { return power.getCurrentThermalStatus(); }
    }
}
