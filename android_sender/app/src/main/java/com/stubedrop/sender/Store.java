package com.stubedrop.sender;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings, kept in SharedPreferences. */
class Store {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("cfg", Context.MODE_PRIVATE); }

    static DropClient.Config load(Context c) {
        SharedPreferences p = prefs(c);
        DropClient.Config cfg = new DropClient.Config();
        cfg.ip = p.getString("ip", "");
        cfg.port = p.getInt("port", 8765);
        cfg.token = p.getString("token", "changeme");
        cfg.https = p.getBoolean("https", true);
        cfg.pinKey = p.getString("pinKey", "");
        cfg.pinFp = p.getString("pinFp", "");
        return cfg;
    }
}
