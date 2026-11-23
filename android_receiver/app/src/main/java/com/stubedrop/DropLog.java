package com.stubedrop;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * The last few drops/messages, held in memory only (never written to disk).
 * Gone when the app process ends.
 */
public class DropLog {
    static final int MAX = 5;
    private static final LinkedList<String> entries = new LinkedList<>();
    private static Runnable listener;

    static synchronized void add(String msg) {
        entries.addFirst(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg);
        while (entries.size() > MAX) entries.removeLast();
        if (listener != null) listener.run();
    }

    static synchronized String text() { return String.join("\n", entries); }

    static synchronized void setListener(Runnable r) { listener = r; }
}
