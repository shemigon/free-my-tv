package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Xiaomi's Mi button: BUTTON_4, which the TV hands to its GlobalKeyReceiver for PatchWall (its log: "keyCode = 191"). */
    static final int KEY_MI = KeyEvent.KEYCODE_BUTTON_4;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    /**
     * Xiaomi's app buttons (remotes sold in Russia), handed to its hot-key receiver (its log: "dealWithHotkey
     * keycode:199"). None has a default here: "Xiaomi default" (empty) leaves the key to the TV.
     */
    static final int KEY_XIAOMI_OKKO = KeyEvent.KEYCODE_BUTTON_13, KEY_XIAOMI_VK = KeyEvent.KEYCODE_BUTTON_1,
            KEY_XIAOMI_RUTUBE = KeyEvent.KEYCODE_BUTTON_16, KEY_XIAOMI_KION = KeyEvent.KEYCODE_BUTTON_11,
            KEY_XIAOMI_WINK = KeyEvent.KEYCODE_BUTTON_10, KEY_XIAOMI_IVI = KeyEvent.KEYCODE_BUTTON_12;
    static final int[] KEYS = RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL,
                    KEY_SONY_PLAY}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : RemoteKeys.XIAOMI ? new int[] {KEY_MI, KEY_XIAOMI_OKKO, KEY_XIAOMI_VK, KEY_XIAOMI_RUTUBE, KEY_XIAOMI_KION,
                    KEY_XIAOMI_WINK, KEY_XIAOMI_IVI}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /**
     * Reads the file again if another process changed it. On Android 11 the screensaver runs in the main process
     * (ScreensaverAlone), which otherwise keeps the values it first read, whatever the settings screen in :home saves.
     */
    @SuppressWarnings("deprecation")
    static void reload() {
        String before = language(), clockBefore = clock();
        app.getSharedPreferences("settings", Context.MODE_MULTI_PROCESS);
        version++;
        if (!language().equals(before) || !clock().equals(clockBefore)) Ui.applyLanguage();
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    /** "auto" (the language's), "12" or "24": hours on the clock, the sun times and the hourly forecast. */
    static String clock() {
        String v = app == null ? "auto" : sp().getString("clock", "auto");
        return v.equals("12") || v.equals("24") ? v : "auto";
    }

    static void setClock(String v) { put("clock", v); Ui.applyLanguage(); }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return sp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { put("sounds", on); }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    /** Your photos (Photos, sent from a computer) on the screensaver and behind the home screen, once imported. */
    static boolean photos() { return sp().getBoolean("photos", true); }
    static void setPhotos(boolean on) { put("photos", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Top buttons

    /**
     * The round buttons at the top right, in order: "live", "inputs" and/or "settings". null until the user edits
     * them (then HomeApps.TOP_DEFAULT applies); empty when they took all three off.
     */
    static List<String> topButtons() {
        String v = sp().getString("topButtons", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setTopButtons(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String p : keys) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("topButtons", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            case KEY_MI: return HomeMode.PKG;   // our home screen: the remote has no other Home button
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so what it reads lives in a tiny file of name=value lines
    // (remote buttons by key code, the TV power options by name), written only by the settings screen's process.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File sharedFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final Map<String, String> shared = new LinkedHashMap<>();   // every line, unknown names kept
    private static long sharedStamp = Long.MIN_VALUE;
    private static boolean writePending;   // a write is queued: the file is older than the map
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();

    /** Reads name=value lines; a missing file is empty, an unreadable one null (not taken for defaults). */
    static Map<String, String> readLines(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (IOException e) {
            AppLog.w("couldn't read " + f.getName() + ": " + e.getMessage());
            return null;
        }
        return out;
    }

    /**
     * Writes name=value lines through a temporary file per process (two can start together after an update), synced
     * before it replaces the old one.
     */
    static boolean writeLines(File f, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        File tmp = new File(f.getParentFile(), f.getName() + "." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            AppLog.w("couldn't write " + f.getName() + ": " + e.getMessage());
            return false;
        }
        if (tmp.renameTo(f)) return true;
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        AppLog.w("couldn't replace " + f.getName());
        return false;
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (sharedFile().exists()) return;
        SharedPreferences old = sp();
        Map<String, String> values = new LinkedHashMap<>();
        for (int key : KEYS) if (old.contains("key" + key)) values.put(String.valueOf(key), old.getString("key" + key, defaultApp(key)));
        if (!values.isEmpty()) writeLines(sharedFile(), values);
    }

    private static long stamp(File f) {
        return f.lastModified() * 31 + f.length();   // 0 without the file: everything takes its default
    }

    /** Reads the file again when it changed (fresh: whatever its stamp says), outside the lock: keys wait on it. */
    private static void refreshShared(boolean fresh) {
        File f = sharedFile();
        long stamp = stamp(f);
        synchronized (shared) {
            // While our own write is queued the map is newer than the file: reading the file would lose the change.
            if (writePending || !fresh && stamp == sharedStamp) return;
        }
        Map<String, String> lines = readLines(f);
        if (lines == null) return;   // unreadable: what was read before stays
        synchronized (shared) {
            if (writePending) return;
            sharedStamp = stamp;
            shared.clear();
            shared.putAll(lines);
        }
    }

    /** Saves in the background (a held arrow doesn't sync the file per step); the latest values win. */
    private static void putShared(String name, String value) {
        refreshShared(false);   // the file's current lines, so none is dropped
        synchronized (shared) {
            shared.put(name, value);
            version++;
            scheduleWrite();
        }
    }

    /** Called with the lock held. */
    private static void scheduleWrite() {
        if (writePending) return;   // the queued write takes this change too
        writePending = true;
        writer.execute(() -> {
            Map<String, String> values;
            synchronized (shared) {
                values = new LinkedHashMap<>(shared);
            }
            boolean ok = writeLines(sharedFile(), values);
            synchronized (shared) {
                writePending = false;
                // Failed: the next read shows what the file really holds, rather than a change :keys never got.
                sharedStamp = ok ? stamp(sharedFile()) : Long.MIN_VALUE;
                if (ok && !values.equals(shared)) scheduleWrite();   // changed while the file was being written
            }
        });
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        String v = sharedOr(String.valueOf(keyCode), null);
        return v != null ? v : defaultApp(keyCode);
    }

    static void setKeyApp(int keyCode, String pkg) { putShared(String.valueOf(keyCode), pkg); }

    // MARK: - TV power (TCL, Free My TV mode; read by TvPower in :keys)

    /** Re-reads the file, whatever its stamp says: TvPower calls it before each decision (15 -> 16 keeps the size). */
    static void reloadShared() { refreshShared(true); }

    private static String sharedOr(String name, String fallback) {
        refreshShared(false);
        synchronized (shared) {
            String v = shared.get(name);
            return v != null ? v : fallback;
        }
    }

    /** The TV turns off when the HDMI device it shows goes to standby. */
    static boolean offWithDevice() { return !"0".equals(sharedOr("off_with_device", "1")); }
    static void setOffWithDevice(boolean on) { putShared("off_with_device", on ? "1" : "0"); }

    /** On turning on: the waking device's input, or the home screen when the remote woke it. Off: the TV's own choice. */
    static boolean turnOnScreen() { return !"0".equals(sharedOr("turn_on_screen", "1")); }
    static void setTurnOnScreen(boolean on) { putShared("turn_on_screen", on ? "1" : "0"); }

    /** Volume set every time the TV turns on, 1 to 100; 0 leaves it alone. */
    static int startVolume() {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(sharedOr("start_volume", "0"))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void setStartVolume(int v) { putShared("start_volume", String.valueOf(Math.max(0, Math.min(100, v)))); }
}
