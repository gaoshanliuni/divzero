package dev.mineagent.runtime.api.media;

import java.util.Objects;

public record MediaScreenBinding(String dimension, int x, int y, int z) {
    public MediaScreenBinding {
        Objects.requireNonNull(dimension, "dimension");
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || Math.abs((long) x) > 30_000_000L || Math.abs((long) z) > 30_000_000L
                || y < -2_048 || y > 2_048) {
            throw new IllegalArgumentException("invalid media screen binding");
        }
    }

    public static MediaScreenBinding parse(String encoded) {
        if (encoded == null || encoded.length() > 256) {
            throw new IllegalArgumentException("invalid media screen binding");
        }
        int separator = encoded.indexOf('@');
        String[] coordinates = separator < 0 ? new String[0] : encoded.substring(separator + 1).split(",", -1);
        if (separator < 1 || coordinates.length != 3) {
            throw new IllegalArgumentException("invalid media screen binding");
        }
        try {
            return new MediaScreenBinding(encoded.substring(0, separator),
                    Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid media screen coordinates", invalid);
        }
    }

    public String encoded() {
        return dimension + "@" + x + "," + y + "," + z;
    }
}
