package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TV's power around its HDMI devices, on TCL TVs in Free My TV mode (Home Settings):
 * - a device the TV is showing goes to standby: the TV turns off a second later, unless the person moved on meanwhile.
 *   Devices only send Inactive Source, which TCL ignores but logs (HdmiCecController); the countdown starts there. A
 *   PS5 sends it when rest starts, ~70 s before its picture stops; the input's state (TvInputManager), which follows
 *   the device's power, is the fallback when the log can't be read.
 *   If the device had switched the TV over from something else when it came on, the TV goes back instead: to that app
 *   or the home screen; from another input, to the home screen (two devices would hand the TV back and forth). If
 *   the TV is still on the input after that, the home screen; still there, it turns off. A device that woke the TV
 *   gets no going back: it came on with it, it turns it off.
 *   TCL switches 11 ms after the device's Active Source, so what was showing comes from what we saw before it: the
 *   foreground app (the service's window events) and the input TCL last opened (its log). An Apple TV's input stays
 *   CONNECTED while it sleeps; only the log tells it went off. Without the log, a PS5's input comes on ~9 s before
 *   TCL switches, which leaves time to ask the shell instead.
 * - the TV turns on: a device woke it over HDMI-CEC → its input; the remote → the home screen. TCL's standby chip eats
 *   the device's Active Source and the TV comes back on the last input; STR_Worker logs why it woke ("wakeup reason =
 *   4" for CEC, 3 for the remote). The log reader usually has it already; reading the log at once takes seconds
 *   right after waking, so a port that came on before the screen did (the device that woke it) opens at once, and
 *   otherwise the home screen does; the log then only corrects a late device.
 * - the volume the person picked, every time the TV turns on.
 * It lives in RemoteKeyService's process (:keys), which is on exactly then, and works on its own thread so the key
 * filter never waits. What needs the shell (the current input, the foreground app, sleep, the log) goes through the
 * TV's ADB with the key it already accepted. The log's lines are parsed in TvLog.
 */
final class TvPower implements Closeable {
    /** The TV's shell. */
    interface Shell {
        /** One command, its output. */
        String run(String command) throws IOException;
        /** A command that keeps printing, line by line, until it ends, fails or stop() is called. */
        void follow(String command, Adb.LineSink sink) throws IOException;
        void stop();
    }

    static boolean supported() { return RemoteKeys.TCL; }

    /** The real one: the TV's own ADB, signed with the key it already allowed (never asks again on the screen). */
    static Shell adb(Context c) {
        return new Shell() {
            private volatile Adb following;
            private volatile boolean stopped;

            @Override public String run(String command) throws IOException {
                try (Adb adb = Adb.connectKnown(c)) {
                    return adb.shell(command);
                }
            }

            @Override public void follow(String command, Adb.LineSink sink) throws IOException {
                try (Adb adb = Adb.connectKnown(c)) {
                    following = adb;
                    if (stopped) return;   // stop() came while connecting
                    adb.follow(command, sink);
                } finally {
                    following = null;
                }
            }

            @Override public void stop() {
                stopped = true;
                Adb a = following;
                if (a != null) a.close();
            }
        };
    }

    private static final long OFF_DELAY = 1_000, WAKE_GUARD = 10_000, REASON_BEFORE = 15_000, REASON_AFTER = 3_000,
            READ_REASON_AFTER = 700, HOME_AFTER = 500, LATE_REASON = 3_000, READ_RETRY = 1_500, DEVICE_WAIT = 8_000, POLL = 500, CHECK_AFTER = 3_000,
            KEY_BEFORE_SCREEN_ON = 2_000, LOG_STALE = 15_000, LOG_RETRY = 5_000, LOG_RETRY_MAX = 120_000;
    private static final int REASON_CEC = 4;
    private static final Pattern RESUMED = Pattern.compile("ResumedActivity: ActivityRecord\\{\\S+ u\\d+ ([^/\\s]+)/");
    private static final Pattern DIGIT = Pattern.compile("(\\d)");
    private static final int HISTORY = 8;
    private static final String CUR_INPUT = "getprop persist.sys.curInput";
    private static final String RESUMED_CMD = "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:'";

    private final Context c;
    private final Shell shell;
    private final HandlerThread thread = new HandlerThread("tv-power");
    private final Handler h;
    private final TvInputManager tim;
    /** Written only from this process: the boot the volume was set for, and whether this TV logs its wake reason. */
    private final File stateFile;
    private volatile long lastUserKeyAt = Long.MIN_VALUE / 2;
    private volatile boolean closed;
    private volatile boolean logReading;   // the log reader is getting lines
    private final Thread logReader = new Thread(this::readLog, "tv-power-log");

    // The rest belongs to the worker thread.
    private final Map<String, Integer> lastState = new HashMap<>();   // HDMI port -> its state as last seen
    private final Map<String, Long> connectedAt = new HashMap<>();   // port -> uptime it became CONNECTED
    private final Map<String, Runnable> pendingOff = new HashMap<>();
    private final Map<String, String> portOf = new HashMap<>();   // input id (port or its CEC device) -> port
    /** Port -> where to go back when its device goes to standby: an app's package, or "" for the home screen. */
    private final Map<String, String> goBack = new HashMap<>();
    private long screenOnAt = Long.MIN_VALUE / 2;
    private int wake;   // bumped at every screen on: a step of an older wake stops
    private int deviceWake = -1;   // the wake whose device input was opened: the home screen stops there
    private String wokePort;   // the port whose device woke the TV, until something else is shown
    private long wokeAt;
    private int loggedReason = -1;   // the last wake reason the log reader saw, and its time
    private long loggedReasonAt = Long.MIN_VALUE / 2;
    private String lastCec = "";   // the same message's second line is skipped
    private long lastCecAt;
    private final List<Seen> apps = new ArrayList<>();   // the foreground app as it changed, oldest first
    private final List<Seen> sessions = new ArrayList<>();   // the inputs TCL opened, oldest first
    private final Map<String, Boolean> appLike = new HashMap<>();
    private String viewerCache;   // the package that shows inputs (com.tcl.tv)

    private static final class Seen {
        final long at;
        final String what;
        Seen(long at, String what) { this.at = at; this.what = what; }
    }

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            guard("screen", () -> {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    screenOn();
                } else {
                    cancelAllOff("screen off");
                    goBack.clear();
                    wokePort = null;
                }
            }).run();
        }
    };

    private final TvInputManager.TvInputCallback inputs = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { guard("input", () -> inputEvent(inputId)).run(); }
        @Override public void onInputAdded(String inputId) { guard("input", () -> { addInputs(); inputEvent(inputId); }).run(); }
        @Override public void onInputUpdated(String inputId) { guard("input", () -> inputEvent(inputId)).run(); }
    };

    TvPower(Context c, Shell shell) {
        this.c = c;
        this.shell = shell;
        stateFile = new File(c.getFilesDir(), "tv-power-state.txt");
        thread.start();
        h = new Handler(thread.getLooper());
        tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        c.registerReceiver(screen, f, null, h);
        if (tim != null) tim.registerCallback(inputs, h);
        h.post(guard("start", this::start));
        logReader.setDaemon(true);
        logReader.start();
    }

    /** From the service's window events: the app now in front. */
    void window(String pkg, long at) {
        h.post(guard("window", () -> noteWindow(pkg, at)));
    }

    /** From the key filter, for every key: cheap. Power keys don't count as using the TV. */
    void userKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_POWER || keyCode == KeyEvent.KEYCODE_TV_POWER || keyCode == KeyEvent.KEYCODE_SLEEP
                || keyCode == KeyEvent.KEYCODE_WAKEUP) return;
        lastUserKeyAt = SystemClock.uptimeMillis();
    }

    @Override public void close() {
        closed = true;
        shell.stop();
        logReader.interrupt();
        try {
            c.unregisterReceiver(screen);
        } catch (RuntimeException ignored) {
        }
        if (tim != null) tim.unregisterCallback(inputs);
        h.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private static Runnable guard(String what, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {   // this process also carries the remote's keys: nothing here may take it down
                AppLog.w("tv-power " + what + ": " + Log.getStackTraceString(t));
            }
        };
    }

    private static long now() { return SystemClock.uptimeMillis(); }

    private void start() {
        for (String port : ports()) {
            int s = state(port);
            lastState.put(port, s);
            AppLog.w("tv-power: " + port + " state " + s);
        }
        try {
            String shown = resumed(shell.run(RESUMED_CMD));
            if (shown != null) noteWindow(shown, now());   // what's in front before any window changes
        } catch (IOException e) {
            AppLog.w("tv-power: couldn't see what's showing: " + e.getMessage());
        }
        // Once per boot, not when the system binds the service again (an update, a crash, the mode switch).
        int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot >= 0 && !String.valueOf(boot).equals(saved("volume_boot"))) {
            applyVolume("boot " + boot);
            save("volume_boot", String.valueOf(boot));
        }
    }

    private String saved(String name) {
        Map<String, String> st = Prefs.readLines(stateFile);
        return st == null ? null : st.get(name);
    }

    private void save(String name, String value) {
        Map<String, String> st = Prefs.readLines(stateFile);
        if (st == null || value.equals(st.get(name))) return;   // unreadable: not overwritten with only this
        st.put(name, value);
        Prefs.writeLines(stateFile, st);
    }

    /** This TV logs why it woke (once seen, for good): without it, the TV's own turn-on screen stays. */
    private boolean reasonSeen() { return "1".equals(saved("reason_seen")); }

    // MARK: - The TV's log

    /** Follows the TV's log for CEC messages and wake reasons, again after a failure (later and later, up to 2 min). */
    private void readLog() {
        long wait = LOG_RETRY;
        while (!closed) {
            long started = now();
            int[] count = {0, 0};   // lines, events
            try {
                shell.follow(TvLog.FOLLOW, line -> {
                    long arrived = now();
                    if (count[0]++ == 0) AppLog.w("tv-power: reading the TV's log");
                    logReading = true;
                    // Parsed here: only the events reach the worker, whatever else the log holds.
                    TvLog.Event e = TvLog.parse(line, System.currentTimeMillis() - arrived);
                    if (e == null) return;
                    count[1]++;
                    boolean cec = e.kind == TvLog.Kind.ACTIVE_SOURCE || e.kind == TvLog.Kind.INACTIVE_SOURCE;
                    if (cec && e.at < arrived - LOG_STALE) {
                        AppLog.w("tv-power: skipped an old line (" + e.kind + " from " + e.value + ", " + (arrived - e.at) + " ms ago)");
                        return;
                    }
                    h.post(guard("log", () -> logEvent(e)));
                });
                if (!closed) AppLog.w("tv-power: the TV's log ended" + counts(started, count));
            } catch (IOException | RuntimeException e) {
                if (!closed) AppLog.w("tv-power: can't read the TV's log" + counts(started, count) + ": " + e);
            }
            logReading = false;
            wait = now() - started > 60_000 ? LOG_RETRY : Math.min(wait * 2, LOG_RETRY_MAX);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String counts(long started, int[] count) {
        return " after " + (now() - started) / 1000 + " s (" + count[0] + " lines, " + count[1] + " events)";
    }

    private void logEvent(TvLog.Event e) {
        switch (e.kind) {
            case ACTIVE_SOURCE:
            case INACTIVE_SOURCE:
                String key = e.kind + " " + e.value;
                if (key.equals(lastCec) && Math.abs(e.at - lastCecAt) < 100) return;   // the same message's second line
                lastCec = key;
                lastCecAt = e.at;
                cec(e.kind == TvLog.Kind.INACTIVE_SOURCE, e.value, e.at);
                break;
            case SESSION:
                add(sessions, new Seen(e.at, e.value));
                if (wokePort != null && e.at > wokeAt && !wokePort.equals(port(e.value))) wokePort = null;   // another input since
                break;
            case WAKE_REASON:
                loggedReasonAt = e.at;
                loggedReason = Integer.parseInt(e.value);
                AppLog.w("tv-power: the log says wakeup reason " + loggedReason);
                save("reason_seen", "1");
                break;
        }
    }

    /** A device at a physical address ("2000": HDMI 2) said it is (or is no longer) the one to show. */
    private void cec(boolean inactive, String address, long at) {
        String port = portAt(address);
        AppLog.w("tv-power: " + (inactive ? "Inactive" : "Active") + " Source from " + address + " (" + port + ")");
        if (port == null) return;
        if (!inactive) {
            cancelOff(port, "the device is the source again");
            noteBefore(port, at);
            return;
        }
        String skip = !interactive() ? "the TV is off" : pendingOff.containsKey(port) ? "already counting" : null;
        if (skip == null) {
            Prefs.reloadShared();
            if (!Prefs.offWithDevice()) skip = "option off";
        }
        if (skip != null) {
            AppLog.w("tv-power: not counting (" + skip + ")");
            return;
        }
        scheduleOff(port, true);
    }

    /** TCL names a device's input "HDMI" + its address + its logical address; else the port whose name's number it is. */
    private String portAt(String address) {
        ports();
        for (Map.Entry<String, String> e : portOf.entrySet()) {
            if (!e.getKey().equals(e.getValue()) && e.getKey().contains("/HDMI" + address)) return e.getValue();
        }
        String number = String.valueOf(Character.digit(address.charAt(0), 16));
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
            Matcher m = DIGIT.matcher(String.valueOf(info.loadLabel(c)));   // the first digit, as HomeApps numbers them
            if (m.find() && m.group(1).equals(number)) return info.getId();
        }
        return null;
    }

    /** The wake reason the log reader saw for this wake, or -1. */
    private int loggedReason() {
        return loggedReasonAt >= screenOnAt - REASON_BEFORE && loggedReasonAt <= screenOnAt + REASON_AFTER ? loggedReason : -1;
    }

    // MARK: - What was showing

    private static void add(List<Seen> list, Seen s) {
        list.add(s);
        if (list.size() > HISTORY) list.remove(0);
    }

    /** The last entry before a time, or null. */
    private static String before(List<Seen> list, long at) {
        String out = null;
        for (Seen s : list) if (s.at < at) out = s.what;
        return out;
    }

    private String viewerPkg() {
        if (viewerCache == null) for (String port : ports()) if ((viewerCache = viewer(port)) != null) break;
        return viewerCache;
    }

    /**
     * Apps on the TV's launcher, the home screen and the input viewer; not TCL's input switcher (com.tcl.sourcemananger,
     * which shows while inputs change and has only a phone launcher), the volume bar, dialogs and the like.
     */
    private boolean appLike(String pkg) {
        Boolean like = appLike.get(pkg);
        if (like == null) {
            like = pkg.equals(HomeMode.PKG) || pkg.equals(viewerPkg())
                    || c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null;
            appLike.put(pkg, like);
        }
        return like;
    }

    private void noteWindow(String pkg, long at) {
        if (!appLike(pkg)) return;
        if (wokePort != null && at > wokeAt && !pkg.equals(viewerPkg())) wokePort = null;   // moved on from that device
        if (!apps.isEmpty() && apps.get(apps.size() - 1).what.equals(pkg)) return;
        add(apps, new Seen(at, pkg));
    }

    /**
     * A device became the source (TCL switches to it at once): where to go back when it goes to standby, from what
     * was showing just before. Not when it woke the TV, nor when its input was already showing.
     */
    private void noteBefore(String port, long at) {
        if (!interactive()) return;
        String skip = at - screenOnAt < WAKE_GUARD ? "while the TV woke" : port.equals(wokePort) ? "and woke the TV" : null;
        if (skip != null) {
            AppLog.w("tv-power: " + port + " is the source " + skip + ": nothing to go back to");
            return;
        }
        String app = before(apps, at);
        if (app == null) {
            AppLog.w("tv-power: " + port + " is the source; what was showing before is unknown");
            return;
        }
        String back = app.equals(HomeMode.PKG) ? "" : app;
        if (app.equals(viewerPkg())) {
            String input = before(sessions, at);
            if (input == null || port.equals(port(input))) {
                AppLog.w("tv-power: " + port + " is the source over input " + input + ": nothing to go back to");
                return;
            }
            back = "";   // another input → the home screen, so two devices never hand the TV back and forth
        }
        remember(port, back, "over " + app);
    }

    private void remember(String port, String back, String why) {
        goBack.put(port, back);
        AppLog.w("tv-power: " + port + " came on " + why + ": going back to "
                + (back.isEmpty() ? "the home screen" : back) + " when it goes to standby");
    }

    // MARK: - Inputs

    private List<String> ports() {
        if (portOf.isEmpty()) addInputs();
        List<String> out = new ArrayList<>();
        for (String port : portOf.values()) if (!out.contains(port)) out.add(port);
        return out;
    }

    /** Adds the TV's HDMI inputs; those of devices removed since stay (they go while the TV sleeps). */
    private void addInputs() {
        if (tim == null) return;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
            portOf.put(info.getId(), info.getParentId() != null ? info.getParentId() : info.getId());
        }
    }

    /** The HDMI port of an input id; null for anything else. */
    private String port(String inputId) {
        if (inputId == null || inputId.isEmpty()) return null;
        if (!portOf.containsKey(inputId)) addInputs();
        return portOf.get(inputId);
    }

    private int state(String port) {
        try {
            return tim.getInputState(port);
        } catch (RuntimeException e) {
            return TvInputManager.INPUT_STATE_DISCONNECTED;
        }
    }

    private void inputEvent(String inputId) {
        String port = port(inputId);
        if (port != null) portState(port, inputId);
    }

    /** The port's own state decides: its CEC device's input comes and goes with the TV's sleep. */
    private void portState(String port, String why) {
        int s = state(port);
        Integer before = lastState.put(port, s);
        if (before != null && before == s) return;
        AppLog.w("tv-power: " + port + " " + before + " -> " + s + " (" + why + ")");
        if (s == TvInputManager.INPUT_STATE_CONNECTED) {
            connectedAt.put(port, now());
            cancelOff(port, "device on again");
            h.post(guard("device on", () -> noteShowing(port)));
            return;
        }
        if (before == null || before != TvInputManager.INPUT_STATE_CONNECTED) return;
        // Waking up, devices that come back report standby first (seen on the TV's log).
        if (now() - screenOnAt < WAKE_GUARD) {
            AppLog.w("tv-power: " + port + " standby right after turning on, ignored");
            return;
        }
        if (pendingOff.containsKey(port)) return;   // already counting from Inactive Source
        Prefs.reloadShared();
        if (!Prefs.offWithDevice()) {
            AppLog.w("tv-power: not counting (option off)");
            return;
        }
        scheduleOff(port, false);
    }

    /** byCec: counting from Inactive Source, when the device's picture is still on. */
    private void scheduleOff(String port, boolean byCec) {
        long since = now();
        Runnable check = guard("off", () -> offCheck(port, byCec, since));
        pendingOff.put(port, check);
        h.postDelayed(check, OFF_DELAY);
        AppLog.w("tv-power: " + port + (byCec ? " is no longer the source" : " went to standby") + ", turning off in "
                + OFF_DELAY + " ms if still on it");
    }

    private void cancelOff(String port, String why) {
        Runnable r = pendingOff.remove(port);
        if (r == null) return;
        h.removeCallbacks(r);
        if (why != null) AppLog.w("tv-power: not turning off (" + why + ")");
    }

    private void cancelAllOff(String why) {
        for (String port : new ArrayList<>(pendingOff.keySet())) cancelOff(port, why);
    }

    private boolean interactive() {
        return ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isInteractive();
    }

    /**
     * A device came on while the TV was on: what it shows now is where to go back when the device goes to standby.
     * Already on the device's input: what was noted before stays (a restart).
     */
    private void noteShowing(String port) {
        if (logReading) return;   // the device's Active Source will tell, with what was showing just before
        // It woke the TV, or came back with it.
        if (!interactive() || now() - screenOnAt < WAKE_GUARD || port.equals(wokePort)) return;
        Prefs.reloadShared();
        if (!Prefs.offWithDevice()) return;
        String out;
        try {
            out = shell.run(CUR_INPUT + "; " + RESUMED_CMD);
        } catch (IOException e) {
            AppLog.w("tv-power: couldn't see what's showing: " + e.getMessage());
            return;
        }
        String current = out.split("\n", 2)[0].trim();
        String resumed = resumed(out);
        if (resumed != null && !appLike(resumed)) resumed = before(apps, now() + 1);   // TCL's input switcher: what was under it
        String viewer = viewer(port);
        if (port.equals(port(current)) && resumed != null && resumed.equals(viewer)) return;
        // Another input (or the tuner) → the home screen: going back there could wake its device, and so on.
        String back = resumed == null || resumed.equals("android") || resumed.equals(viewer) || resumed.equals(HomeMode.PKG)
                ? "" : resumed;
        remember(port, back, "over " + resumed + " (input " + current + ")");
    }

    // MARK: - Turning off

    /** since: when the countdown started; a key pressed since means the person is using the TV. */
    private void offCheck(String port, boolean byCec, long since) {
        pendingOff.remove(port);
        if (closed) return;   // Original mode since
        Prefs.reloadShared();
        String skip = !Prefs.offWithDevice() ? "option off"
                : !interactive() ? "the TV is already off"
                : !byCec && state(port) == TvInputManager.INPUT_STATE_CONNECTED ? "the device is on again"
                : lastUserKeyAt >= since ? "the remote was used" : null;
        if (skip != null) {
            AppLog.w("tv-power: not turning off (" + skip + ")");
            return;
        }
        String viewer = viewer(port);
        String out;
        try {
            out = shell.run(CUR_INPUT + "; " + RESUMED_CMD);
        } catch (IOException e) {
            AppLog.w("tv-power: not turning off (no ADB: " + e.getMessage() + ")");
            return;
        }
        String current = out.split("\n", 2)[0].trim();
        String resumed = resumed(out);
        AppLog.w("tv-power: off check " + port + ": input " + current + ", showing " + resumed + ", viewer " + viewer);
        if (!port.equals(port(current))) skip = "on another input";
        else if (viewer == null || viewer.equals("android") || !viewer.equals(resumed)) skip = "the TV isn't showing the input";
        else if (lastUserKeyAt >= since) skip = "the remote was used";
        if (skip != null) {
            AppLog.w("tv-power: not turning off (" + skip + ")");
            return;
        }
        String back = goBack.remove(port);
        if (back != null) leave(port, back, wake);
        else sleepIfStill(current);
    }

    /** Goes back to an app ("": the home screen); still on the port a moment later: the home screen, then off. */
    private void leave(String port, String back, int at) {
        if (closed) return;
        Intent i = back.isEmpty() ? null : RemoteKeys.launchIntent(c.getPackageManager(), back);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        boolean home = i.hasCategory(Intent.CATEGORY_HOME);
        AppLog.w("tv-power: going back to " + (home ? "the home screen" : back));
        long sent = now();
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        h.postDelayed(guard("back", () -> {
            if (closed || at != wake || !interactive() || lastUserKeyAt >= sent) return;
            String out;
            try {
                out = shell.run(CUR_INPUT + "; " + RESUMED_CMD);
            } catch (IOException e) {
                AppLog.w("tv-power: couldn't check going back: " + e.getMessage());
                return;
            }
            String current = out.split("\n", 2)[0].trim();
            String resumed = resumed(out);
            if (!port.equals(port(current)) || resumed == null || !resumed.equals(viewer(port))) return;   // it left
            if (!home) {
                AppLog.w("tv-power: still on " + port + " (showing " + resumed + "): the home screen instead");
                leave(port, "", at);
                return;
            }
            AppLog.w("tv-power: still on " + port + " after the home screen: turning off");
            sleepIfStill(current);
        }), CHECK_AFTER);
    }

    /** Sleeps if the input is still current: read once more in the same shell, just before. */
    private void sleepIfStill(String current) {
        if (closed) return;
        try {
            String out = shell.run("if [ \"$(" + CUR_INPUT + ")\" = '" + current.replace("'", "")
                    + "' ]; then input keyevent KEYCODE_SLEEP && echo slept; else echo changed; fi").trim();
            AppLog.w(out.endsWith("slept") ? "tv-power: turned the TV off"
                    : out.equals("changed") ? "tv-power: not turning off (the input changed)"
                    : "tv-power: couldn't turn off: " + out);
        } catch (IOException e) {
            AppLog.w("tv-power: couldn't turn off: " + e.getMessage());
        }
    }

    /** The package that shows passthrough inputs (com.tcl.tv on TCL), or null. */
    private String viewer(String port) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(passthrough(port), 0);
        return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
    }

    private static Intent passthrough(String port) {
        return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(port));
    }

    private static String resumed(String dumpsys) {
        Matcher m = RESUMED.matcher(dumpsys);
        return m.find() ? m.group(1) : null;
    }

    // MARK: - Turning on

    private void screenOn() {
        screenOnAt = now();
        int at = ++wake;
        cancelAllOff("turned on");
        goBack.clear();
        wokePort = null;
        applyVolume("turned on");
        Prefs.reloadShared();
        if (!Prefs.turnOnScreen()) return;
        boolean early = loggedReason >= 0 || reasonSeen();
        AppLog.w("tv-power: turned on (reason " + loggedReason() + " from the log reader, fast path " + early + ")");
        if (early) {
            String woke = firstOn(screenOnAt);
            if (woke != null) {
                AppLog.w("tv-power: " + woke + " came on before the screen: it woke the TV");
                openInput(at, woke, true);
                return;
            }
            if (loggedReason() == REASON_CEC) {
                AppLog.w("tv-power: woken over HDMI-CEC (log), waiting for the device");
                findDevice(at, now() + DEVICE_WAIT);
                return;
            }
            // The remote, most likely: the log may still show a device that came on late (it then wins).
            h.postDelayed(guard("wake", () -> openHome(at, true)), HOME_AFTER);
            if (logReading) {
                h.postDelayed(guard("wake", () -> {
                    if (at == wake && loggedReason() == REASON_CEC) findDevice(at, now() + DEVICE_WAIT);
                }), LATE_REASON);
                return;
            }
        }
        // After the home screen above: the read below takes seconds, and this thread waits for it.
        h.postDelayed(guard("wake", () -> readReason(at, true, early)), READ_REASON_AFTER);
    }

    /** The first port that came on between the wake guard before screen on and until; null if none. */
    private String firstOn(long until) {
        String best = null;
        for (String port : ports()) {
            Long on = connectedAt.get(port);
            if (on == null || on < screenOnAt - WAKE_GUARD || on > until) continue;
            AppLog.w("tv-power: candidate " + port + " on at " + (on - screenOnAt) + " ms");
            if (best == null || on < connectedAt.get(best)) best = port;
        }
        return best;
    }

    /** A button pressed to turn on (Netflix…) can arrive before the screen-on broadcast. */
    private boolean keySinceScreenOn() {
        return lastUserKeyAt >= screenOnAt - KEY_BEFORE_SCREEN_ON;
    }

    /** early: the home screen is already on its way; only a device wake changes it. */
    private void readReason(int at, boolean first, boolean early) {
        if (at != wake) return;
        if (keySinceScreenOn()) {
            AppLog.w("tv-power: turned on with a button: the screen stays as it is");
            return;
        }
        int known = loggedReason();
        if (known >= 0) {
            AppLog.w("tv-power: woke, reason " + known + " (log reader)");
            if (known == REASON_CEC) findDevice(at, now() + DEVICE_WAIT);
            else if (!early) openHome(at, true);
            return;
        }
        String log;
        try {
            log = shell.run(TvLog.REASONS);
        } catch (IOException e) {
            if (first) {
                AppLog.w("tv-power: wake reason unreadable, trying again (" + e.getMessage() + ")");
                h.postDelayed(guard("wake", () -> readReason(at, false, early)), READ_RETRY);
                return;
            }
            AppLog.w("tv-power: wake reason unreadable (" + e.getMessage() + ")");
            log = "";
        }
        int reason = -1;
        boolean anyLine = false;
        for (String line : log.split("\n")) {
            TvLog.Event e = TvLog.parse(line, 0);
            if (e == null || e.kind != TvLog.Kind.WAKE_REASON) continue;
            anyLine = true;
            // STR_Worker can log just after the screen comes on.
            if (e.at >= screenOnAt - REASON_BEFORE && e.at <= screenOnAt + REASON_AFTER) reason = Integer.parseInt(e.value);
        }
        if (anyLine) save("reason_seen", "1");
        if (!reasonSeen()) {
            AppLog.w("tv-power: this TV doesn't log why it woke: its own screen stays");
            return;
        }
        AppLog.w("tv-power: woke, reason " + reason);
        if (reason == REASON_CEC) {
            findDevice(at, now() + DEVICE_WAIT);
        } else if (!early) {
            openHome(at, true);
        }
    }

    /** The device that woke the TV is the port that came on around then; the first, as a later one may be one it woke. */
    private void findDevice(int at, long until) {
        if (at != wake || keySinceScreenOn()) return;
        for (String port : ports()) portState(port, "poll");   // a missed callback still counts
        String best = firstOn(now());
        if (best == null) {
            if (now() < until) h.postDelayed(guard("wake", () -> findDevice(at, until)), POLL);
            else AppLog.w("tv-power: woken over HDMI-CEC, but no device came on");
            return;
        }
        openInput(at, best, true);
    }

    private void openInput(int at, String port, boolean first) {
        if (at != wake || keySinceScreenOn()) return;
        deviceWake = at;
        wokePort = port;
        wokeAt = now();
        AppLog.w("tv-power: opening " + port);
        c.startActivity(passthrough(port).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        h.postDelayed(guard("wake", () -> {
            if (at != wake || keySinceScreenOn()) return;
            String current;
            try {
                current = shell.run(CUR_INPUT).trim();
            } catch (IOException e) {
                AppLog.w("tv-power: couldn't check the input: " + e.getMessage());
                return;
            }
            boolean ok = port.equals(port(current));
            AppLog.w("tv-power: input now " + current + (ok ? "" : first ? ", trying again" : ", giving up"));
            if (!ok && first) openInput(at, port, false);
        }), CHECK_AFTER);
    }

    private void openHome(int at, boolean first) {
        if (at != wake || deviceWake == at || keySinceScreenOn()) return;
        AppLog.w("tv-power: opening the home screen");
        c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        h.postDelayed(guard("wake", () -> {
            if (at != wake || deviceWake == at || keySinceScreenOn()) return;
            String shown;
            try {
                shown = resumed(shell.run(RESUMED_CMD));
            } catch (IOException e) {
                AppLog.w("tv-power: couldn't check the screen: " + e.getMessage());
                return;
            }
            boolean ok = HomeMode.PKG.equals(shown);
            AppLog.w("tv-power: showing " + shown + (ok ? "" : first ? ", trying again" : ", giving up"));
            if (!ok && first) openHome(at, false);
        }), CHECK_AFTER);
    }

    // MARK: - Volume

    private void applyVolume(String why) {
        Prefs.reloadShared();
        int v = Prefs.startVolume();
        if (v <= 0) return;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int target = Math.min(v, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        AppLog.w("tv-power: volume " + target + " (" + why + ")");
    }
}
