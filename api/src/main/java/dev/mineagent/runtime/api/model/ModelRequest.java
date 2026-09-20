package dev.mineagent.runtime.api.model;

import java.util.Objects;

public record ModelRequest(ModelCapability capability, String prompt, java.util.List<ModelImage> images) {
    public ModelRequest(ModelCapability capability,String prompt){this(capability,prompt,java.util.List.of());}
    public ModelRequest {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(prompt, "prompt");
        images=java.util.List.copyOf(images);
        if(images.size()>1)throw new IllegalArgumentException("MODEL_IMAGE_COUNT");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
    }
}
