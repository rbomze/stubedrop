package com.stubedrop;

import android.content.*;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        // only restart if the user left the server switched on
        if (!c.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("enabled", true)) return;
        try { c.startForegroundService(new Intent(c, ServerService.class)); } catch (Exception ignored) {}
    }
}
