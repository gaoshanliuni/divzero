package dev.mineagent.runtime.client.studio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CodeStudioTextTools {
    private static final int MAX_SOURCE_LENGTH = 1_000_000;
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "record", "sealed", "permits", "non-sealed", "var", "yield");
    private static final Set<String> JAVASCRIPT_KEYWORDS = Set.of(
            "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
            "do", "else", "export", "extends", "false", "finally", "for", "function", "if", "import", "in",
            "instanceof", "let", "new", "null", "return", "static", "super", "switch", "this", "throw",
            "true", "try", "typeof", "undefined", "var", "void", "while", "with", "yield");
    private static final List<String> JAVA_COMPLETIONS = List.of(
            "public", "private", "protected", "class", "interface", "record", "extends", "implements",
            "return", "new", "import", "package", "static", "final", "void", "int", "String", "Map", "List",
            "@Override");
    private static final List<String> JAVASCRIPT_COMPLETIONS = List.of(
            "const", "let", "function", "return", "class", "extends", "new", "async", "await", "if", "else",
            "for", "while", "try", "catch", "host", "console");

    public List<Integer> search(String source, String query, boolean caseSensitive) {
        requireSource(source);
        if (query == null || query.isEmpty()) {
            return List.of();
        }
        if (query.length() > 256) {
            throw new IllegalArgumentException("search query is too long");
        }
        String haystack = caseSensitive ? source : source.toLowerCase(Locale.ROOT);
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        var matches = new ArrayList<Integer>();
        for (int from = 0; from <= haystack.length() - needle.length();) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                break;
            }
            matches.add(index);
            if (matches.size() >= 10_000) {
                break;
            }
            from = index + Math.max(1, needle.length());
        }
        return List.copyOf(matches);
    }

    public ReplaceResult replaceAll(String source, String query, String replacement, boolean caseSensitive) {
        requireSource(source);
        if (replacement == null || replacement.length() > MAX_SOURCE_LENGTH || query == null || query.isEmpty()) {
            return new ReplaceResult(source, 0);
        }
        List<Integer> matches = search(source, query, caseSensitive);
        if (matches.isEmpty()) {
            return new ReplaceResult(source, 0);
        }
        long estimated = (long) source.length() + (long) matches.size() * (replacement.length() - query.length());
        if (estimated > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException("replacement result is too large");
        }
        var output = new StringBuilder((int) estimated);
        int cursor = 0;
        for (int match : matches) {
            output.append(source, cursor, match).append(replacement);
            cursor = match + query.length();
        }
        output.append(source, cursor, source.length());
        return new ReplaceResult(output.toString(), matches.size());
    }

    public List<String> complete(String source, int cursor, CodeLanguage language) {
        requireSource(source);
        if (cursor < 0 || cursor > source.length() || language == null) {
            throw new IllegalArgumentException("invalid completion request");
        }
        int start = cursor;
        while (start > 0 && isIdentifier(source.charAt(start - 1))) {
            start--;
        }
        String prefix = source.substring(start, cursor);
        if (prefix.isEmpty()) {
            return List.of();
        }
        return (language == CodeLanguage.JAVA ? JAVA_COMPLETIONS : JAVASCRIPT_COMPLETIONS).stream()
                .filter(candidate -> candidate.startsWith(prefix) && !candidate.equals(prefix))
                .limit(20).toList();
    }

    public List<SyntaxToken> highlight(String source, CodeLanguage language) {
        requireSource(source);
        if (language == null) {
            throw new IllegalArgumentException("language is required");
        }
        Set<String> keywords = language == CodeLanguage.JAVA ? JAVA_KEYWORDS : JAVASCRIPT_KEYWORDS;
        var tokens = new ArrayList<SyntaxToken>();
        int index = 0;
        while (index < source.length() && tokens.size() < 100_000) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                int end = source.indexOf('\n', index + 2);
                end = end < 0 ? source.length() : end;
                tokens.add(new SyntaxToken(index, end, SyntaxTokenType.COMMENT));
                index = end;
            } else if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int closing = source.indexOf("*/", index + 2);
                int end = closing < 0 ? source.length() : closing + 2;
                tokens.add(new SyntaxToken(index, end, SyntaxTokenType.COMMENT));
                index = end;
            } else if (current == '\'' || current == '"' || (current == '`' && language == CodeLanguage.JAVASCRIPT)) {
                int end = scanString(source, index, current);
                tokens.add(new SyntaxToken(index, end, SyntaxTokenType.STRING));
                index = end;
            } else if (Character.isDigit(current)) {
                int end = index + 1;
                while (end < source.length() && (Character.isLetterOrDigit(source.charAt(end))
                        || ".xX_".indexOf(source.charAt(end)) >= 0)) {
                    end++;
                }
                tokens.add(new SyntaxToken(index, end, SyntaxTokenType.NUMBER));
                index = end;
            } else if (Character.isJavaIdentifierStart(current) || current == '$') {
                int end = index + 1;
                while (end < source.length() && isIdentifier(source.charAt(end))) {
                    end++;
                }
                if (keywords.contains(source.substring(index, end))) {
                    tokens.add(new SyntaxToken(index, end, SyntaxTokenType.KEYWORD));
                }
                index = end;
            } else {
                index++;
            }
        }
        return List.copyOf(tokens);
    }

    private static int scanString(String source, int start, char quote) {
        boolean escaped = false;
        for (int index = start + 1; index < source.length(); index++) {
            char value = source.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == quote) {
                return index + 1;
            }
        }
        return source.length();
    }

    private static boolean isIdentifier(char value) {
        return Character.isJavaIdentifierPart(value) || value == '$';
    }

    private static void requireSource(String source) {
        if (source == null || source.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException("source is too large");
        }
    }
}
