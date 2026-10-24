package io.freemytv;

import android.graphics.RectF;

/**
 * The picks on the light home screen: a row of up to Recs.ROW cards with VISIBLE in view, the first of them wide and the
 * others tall. → past the last one in view slides the row by one card, ← past the first slides it back; the wide slot
 * always holds the first card in view. Knows where the row stands and where each card is, while it slides too.
 */
final class CardRow {
    static final int VISIBLE = 3;

    private final Spring pos = new Spring(0.34f, 0.86f, 0);   // the first card in view, sliding
    private int first, count;
    private float left, top, wide, tall, gap, height;

    void layout(float left, float top, float wide, float tall, float gap, float height) {
        this.left = left;
        this.top = top;
        this.wide = wide;
        this.tall = tall;
        this.gap = gap;
        this.height = height;
    }

    int first() {
        return first;
    }

    /** A new list: the row stays where it was while it can. */
    void setCount(int n) {
        count = n;
        int last = Math.max(0, n - VISIBLE);
        if (first > last) first = last;
        pos.target = first;
    }

    /** Back to the start, without sliding (a new tab). */
    void reset() {
        first = 0;
        pos.snap(0);
    }

    /** The card the focus goes to from `focus` (dx = ±1); the row slides when that card is out of view. */
    int move(int focus, int dx) {
        int next = Math.max(0, Math.min(count - 1, focus + dx));
        show(next);
        return next;
    }

    /** Slides the row so that card i is in view. */
    void show(int i) {
        if (i < first) first = i;
        else if (i >= first + VISIBLE) first = i - VISIBLE + 1;
        pos.target = first;
    }

    boolean step(float dt) {
        return pos.step(dt);
    }

    /**
     * Where card i is now, in slots: 0 the wide one, 1 and 2 the tall ones, 3 the next card peeking at the right edge,
     * below 0 leaving to the left.
     */
    float slot(int i) {
        return i - pos.value;
    }

    /** The first and last card worth drawing now (the others are off screen). */
    int drawFrom() {
        return Math.max(0, (int) Math.floor(pos.value) - 1);
    }

    int drawTo() {
        return Math.min(count - 1, (int) Math.ceil(pos.value) + VISIBLE);
    }

    /** The cards to keep ready once the row stops: those in view and one at each side. */
    int readyFrom() {
        return Math.max(0, first - 1);
    }

    int readyTo() {
        return Math.min(count - 1, first + VISIBLE);
    }

    /** The card at slot t: between the wide slot and the first tall one it narrows (or widens) as it moves. */
    void rect(float t, RectF out) {
        float x = t <= 1 ? left + t * (wide + gap) : left + wide + gap + (t - 1) * (tall + gap);
        float w = t <= 0 ? wide : t >= 1 ? tall : wide + t * (tall - wide);
        out.set(Math.round(x), Math.round(top), Math.round(x + w), Math.round(top + height));
    }

    /** Card i once the row stops. */
    void restRect(int i, RectF out) {
        rect(i - first, out);
    }
}
