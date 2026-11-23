package com.stubedrop.sender;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the YouTube link in whatever text another app shares. Plain Java (no Android classes). */
public class LinkExtractor {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);

    static boolean isYouTubeHost(String h) {
        h = h.toLowerCase();
        return h.equals("youtu.be") || h.equals("youtube.com") || h.endsWith(".youtube.com");
    }

    /** First YouTube link in the text (as https), or null. */
    public static String extract(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String u = m.group().replaceAll("[)\\]}>.,;:!?'\"]+$", "");
            try {
                String host = URI.create(u).getHost();
                if (host != null && isYouTubeHost(host)) return u.replaceFirst("(?i)^http:", "https:");
            } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }
}
