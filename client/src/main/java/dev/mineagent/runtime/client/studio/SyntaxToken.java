package dev.mineagent.runtime.client.studio;

public record SyntaxToken(int start, int end, SyntaxTokenType type) {
    public SyntaxToken {
        if (start < 0 || end <= start || type == null) {
            throw new IllegalArgumentException("invalid syntax token");
        }
    }
}
