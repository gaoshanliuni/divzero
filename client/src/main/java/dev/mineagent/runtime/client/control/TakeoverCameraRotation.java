package dev.mineagent.runtime.client.control;

/** Render-only interpolation between completed body ticks; never feeds back into aim or input. */
public final class TakeoverCameraRotation {
    public record Angles(float yaw, float pitch) {}

    private Angles previous;
    private Angles current;

    public void reset(float yaw, float pitch) {
        previous = current = new Angles(yaw, pitch);
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
        float alpha = (float) Math.clamp(partialTick, 0, 1);
        return new Angles(previous.yaw() + shortestDelta(previous.yaw(), current.yaw()) * alpha,
                previous.pitch() + (current.pitch() - previous.pitch()) * alpha);
    }

    public static float shortestDelta(float from, float to) {
        float delta = (to - from) % 360;
        if (delta >= 180) delta -= 360;
        if (delta < -180) delta += 360;
        return delta;
    }
}
