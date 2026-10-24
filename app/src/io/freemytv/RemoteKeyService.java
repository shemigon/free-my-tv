package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 * On TCL it also hosts TvPower, which is on exactly when this service is (Free My TV mode).
 */
public class RemoteKeyService extends AccessibilityService {
    private TvPower power;

    @Override protected void onServiceConnected() {
        if (power == null && TvPower.supported()) power = new TvPower(this, TvPower.adb(this));
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        TvPower p = power;
        if (p != null) p.userKey(event.getKeyCode());
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        TvPower p = power;
        if (p != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            p.window(event.getPackageName().toString(), event.getEventTime());
        }
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (power != null) power.close();
        power = null;
        super.onDestroy();
    }
}
