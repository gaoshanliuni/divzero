package dev.mineagent.runtime.client.control;

/** Render-only interpolation between completed body ticks; never feeds back into aim or input. */
public final class TakeoverCameraRotation {
    public record Angles(float yaw, float pitch) {}

    private Angles previous;
    private Angles current;
    private Angles observer;

    public void reset(float yaw, float pitch) {
        previous = current = new Angles(yaw, pitch);
        observer = null;
    }

    public void tick(float yaw, float pitch) {
        if (current == null) {
            reset(yaw, pitch);
            return;
        }
        previous = current;
        current = new Angles(yaw, pitch);
    }

    public Angles sample(double partialTick) {
        if (current == null) throw new IllegalStateException("CAMERA_NOT_INITIALIZED");
        if (observer != null) return observer;
        float alpha = (float) Math.clamp(partialTick, 0, 1);
        return new Angles(previous.yaw() + shortestDelta(previous.yaw(), current.yaw()) * alpha,
                previous.pitch() + (current.pitch() - previous.pitch()) * alpha);
    }

    public boolean observingFreely() { return observer != null; }

    /** Start from the last rendered pose; subsequent body ticks cannot rotate this view. */
    public void turn(Angles displayed, double yawDelta, double pitchDelta) {
        if (!Double.isFinite(yawDelta) || !Double.isFinite(pitchDelta)) return;
        var base = observer == null ? displayed : observer;
        observer = new Angles(shortestDelta(0, (float)(base.yaw() + yawDelta)),
                (float)Math.clamp(base.pitch() + pitchDelta, -90, 90));
    }

    /** Return immediately to current real aim, then resume frame interpolation. */
    public void follow(float yaw, float pitch) { reset(yaw, pitch); }

    public static float shortestDelta(float from, float to) {
        float delta = (to - from) % 360;
        if (delta >= 180) delta -= 360;
        if (delta < -180) delta += 360;
        return delta;
    }
}
