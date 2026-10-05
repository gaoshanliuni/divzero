package dev.mineagent.runtime.legacy189;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Java 8 inference for the unchanged shipped 16/24 policy checkpoint. */
public final class LegacyPolicy {
    private static LegacyPolicy instance;
    private final Weights weights;
    private final String hash;
    private final String source;
    private long inferences;
    private LegacyPolicy(byte[] bytes) throws Exception {
        source = new String(bytes, StandardCharsets.UTF_8);
        weights = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), Weights.class);
        if (!"divzero-admissible-action-value/2".equals(weights.schema) || weights.version < 1 || weights.hidden.length != 24 || weights.bias.length != 24 || weights.output.length != 24) throw new IllegalStateException("POLICY_SHAPE");
        for (int i = 0; i < 24; i++) { if (weights.hidden[i].length != 16) throw new IllegalStateException("POLICY_INPUTS"); for (double value : weights.hidden[i]) finite(value); finite(weights.bias[i]); finite(weights.output[i]); }
        finite(weights.outputBias);
        StringBuilder digest = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format(Locale.ROOT, "%02x", b & 255)); hash = digest.toString();
    }
    public static synchronized LegacyPolicy get() {
        if (instance != null) return instance;
        try (InputStream stream = LegacyPolicy.class.getResourceAsStream("/dev/mineagent/runtime/policy/pretrained.json")) {
            if (stream == null) throw new IOException("POLICY_MISSING");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) { if (bytes.size() + count > 1024 * 1024) throw new IOException("POLICY_SIZE"); bytes.write(buffer, 0, count); }
            instance = new LegacyPolicy(bytes.toByteArray()); return instance;
        } catch (Exception failure) { throw new IllegalStateException("POLICY_LOAD_FAILED", failure); }
    }
    public static LegacyPolicy parse(String json) {
        if (json == null || json.length() > 1024 * 1024) throw new IllegalArgumentException("POLICY_SIZE");
        try { return new LegacyPolicy(json.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception invalid) { throw new IllegalArgumentException("POLICY_INVALID", invalid); }
    }
    public String json() { return source; }
    public double cost(double[] features) {
        if (features.length != 16) throw new IllegalArgumentException("POLICY_FEATURES");
        double output = weights.outputBias;
        for (int row = 0; row < 24; row++) {
            double hidden = weights.bias[row];
            for (int column = 0; column < 16; column++) hidden += weights.hidden[row][column] * (Double.isFinite(features[column]) ? clamp(features[column], -2, 2) : 0);
            output += weights.output[row] * Math.tanh(hidden);
        }
        inferences++; return 1 / (1 + Math.exp(-clamp(output, -30, 30)));
    }
    public String hash() { return hash; }
    public long version() { return weights.version; }
    public long inferences() { return inferences; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static void finite(double value) { if (!Double.isFinite(value) || Math.abs(value) > 64) throw new IllegalArgumentException("POLICY_WEIGHT"); }
    private static final class Weights { String schema, provenance; long version; double[][] hidden; double[] bias, output; double outputBias; }
}
