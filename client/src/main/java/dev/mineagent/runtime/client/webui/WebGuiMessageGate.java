package dev.mineagent.runtime.client.webui;

/** Actual browser identity + main frame + exact document, never identity claimed in JSON. */
public final class WebGuiMessageGate {
    private Object browser;
    private String document;
    public synchronized void bind(Object browser, String document) {
        this.browser = java.util.Objects.requireNonNull(browser);
        this.document = java.util.Objects.requireNonNull(document);
    }
    public synchronized boolean owns(Object browser) { return this.browser != null && this.browser == browser; }
    public synchronized boolean accepts(Object browser, boolean main, String url, int length) {
        return owns(browser) && main && document.equals(url) && length > 0 && length <= 65536;
    }
    public synchronized boolean documentAllowed(Object browser, String url, boolean ready) {
        return owns(browser) && (document.equals(url) || (!ready && (url == null || url.isEmpty() || url.equals("about:blank"))));
    }
    public synchronized void clear() { browser = null; document = null; }
}
