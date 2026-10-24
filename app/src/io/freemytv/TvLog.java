package io.freemytv;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lines of the TV's log that TvPower reads (`logcat -v <clock>,uid`), parsed. Any app may log under any tag, so
 * only lines from the system or root count: TCL's HdmiCecController and TvInputManagerService log as system (1000),
 * STR_Worker as root (rtkd). No Android types here, so a captured log can be run through it on a computer.
 */
final class TvLog {
    private TvLog() {}

    // In wall-clock time: a long-running logcat's monotonic stamps go wrong after the TV sleeps (seen on the TV).
    static final String FOLLOW = "logcat -v epoch,uid -T 1 -s HdmiCecController STR_Worker TvInputManagerService";
    /** One read, in CLOCK_MONOTONIC: the stamps are SystemClock.uptimeMillis (clock 0). */
    static final String REASONS = "logcat -d -v monotonic,uid -s STR_Worker";

    enum Kind { ACTIVE_SOURCE, INACTIVE_SOURCE, SESSION, WAKE_REASON }

    static final class Event {
        final Kind kind;
        final long at;   // uptime
        /** The device's physical address ("2000": HDMI 2), the input id TCL opened, or the wake reason. */
        final String value;

        Event(Kind kind, long at, String value) {
            this.kind = kind;
            this.at = at;
            this.value = value;
        }

        @Override public String toString() { return kind + " " + value + " at " + at; }
    }

    /** "1792840903.319  1000   635   635 D HdmiCecController: ...": stamp, uid, pid, tid, level. */
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d{3})\\s+(\\S+)\\s+\\d+\\s+\\d+\\s+\\S\\s(.*)$");
    /** TCL logs each CEC message twice (two debug tags): "...<InActive Source> src: 8, dst: 0, params: 20 00". */
    private static final Pattern CEC = Pattern.compile(
            "handleIncomingCecCommand command:<(InActive Source|Active Source)> src: \\d+, dst: \\d+, params: ([0-9A-Fa-f]{2}) ([0-9A-Fa-f]{2})");
    private static final Pattern SESSION = Pattern.compile("createSessionInternalLocked\\(inputId=([^)]+)\\)");
    private static final Pattern REASON = Pattern.compile("wakeup reason = (\\d+)");

    /** The line's event, its stamp minus clock (wall clock → uptime); null for anything else or another writer. */
    static Event parse(String line, long clock) {
        Matcher l = LINE.matcher(line);
        if (!l.find() || !trusted(l.group(3))) return null;
        long at = Long.parseLong(l.group(1)) * 1000 + Long.parseLong(l.group(2)) - clock;
        String text = l.group(4);
        Matcher m = CEC.matcher(text);
        if (m.find()) {
            return new Event(m.group(1).startsWith("In") ? Kind.INACTIVE_SOURCE : Kind.ACTIVE_SOURCE, at, m.group(2) + m.group(3));
        }
        m = SESSION.matcher(text);
        if (m.find()) return new Event(Kind.SESSION, at, m.group(1));
        m = REASON.matcher(text);
        if (m.find()) return new Event(Kind.WAKE_REASON, at, m.group(1));
        return null;
    }

    /** Root and the system's own users (below the shell's 2000); apps start at 10000. */
    private static boolean trusted(String uid) {
        if (uid.equals("root") || uid.equals("system")) return true;
        try {
            return Integer.parseInt(uid) < 2000;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
