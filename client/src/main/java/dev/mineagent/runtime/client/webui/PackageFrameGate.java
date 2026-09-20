package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.ui.UiProtocol.Session;
import java.util.*;

/** Native host mapping. Actor/session IDs in webpage messages never participate in route selection. */
public final class PackageFrameGate {
    private record Frame(Object browser, long frameId, String url, Session session) {}
    private final Map<String, Frame> frames = new HashMap<>();
    public synchronized void bind(Object browser, long frameId, String url, Session session) {
        Objects.requireNonNull(browser); Objects.requireNonNull(url); Objects.requireNonNull(session);
        if (frames.size() >= 64 && !frames.containsKey(session.binding().viewId())) throw new IllegalStateException("CONTENT_VIEW_BUDGET");
        frames.put(session.binding().viewId(), new Frame(browser, frameId, url, session));
    }
    public synchronized Optional<Session> accept(Object browser, long frameId, boolean main, String url, int messageLength) {
        if (main || messageLength < 0 || messageLength > 65_536) return Optional.empty();
        return frames.values().stream().filter(f -> f.browser == browser && f.frameId == frameId && f.url.equals(url)).map(Frame::session).findFirst();
    }
    public synchronized void close(String viewId) { frames.remove(viewId); }
    public synchronized void clear() { frames.clear(); }
}
