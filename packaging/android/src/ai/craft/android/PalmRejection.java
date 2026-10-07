package ai.craft.android;

/** Device-independent policy; Android's MotionEvent adapter owns the pointer cancellation. */
final class PalmRejection {
    private boolean contact;
    private boolean hovering;
    private long lastPen = Long.MIN_VALUE;
    private boolean rejectSequence;

    void pen(int action, long now) {
        lastPen = now;
        if (action == 0 || action == 5) contact = true;
        if (action == 1 || action == 3 || action == 6) contact = false;
        if (action == 7 || action == 9) hovering = true;
        if (action == 3 || action == 10) hovering = false;
    }
    boolean nearby(long now) { return contact || hovering || (lastPen != Long.MIN_VALUE && now - lastPen < 250); }
    boolean finger(int action, long now) {
        if (action == 0) rejectSequence = nearby(now);
        boolean reject = rejectSequence || nearby(now);
        if (action == 1 || action == 3) rejectSequence = false;
        return reject;
    }
    void cancelFingerSequence() { rejectSequence = true; }
    void reset() { contact = false; hovering = false; lastPen = Long.MIN_VALUE; rejectSequence = false; }
}
