package dev.mineagent.runtime.core.compile;

import java.lang.classfile.*;
import java.util.*;

/** Metadata only: no class initialization, constants, method bodies or inferred source mapping. */
public final class ClassFileSymbols {
    private ClassFileSymbols() {}
    public record Member(String kind, String name, String descriptor, String signature, int flags,List<String> exceptions) {public Member{exceptions=List.copyOf(exceptions);}}
    public record Summary(String name, String superclass, List<String> interfaces, int majorVersion,
            int flags, String signature, List<Member> members, int total, int offset, int nextOffset, boolean more) {}
    private record Candidate(String kind, String name, String descriptor, AttributedElement model, int flags) {}

    private static String signature(AttributedElement model) {
        return model.findAttribute(Attributes.signature()).map(a -> a.signature().stringValue()).orElse("");
    }

    public static Summary inspect(byte[] bytes, String search, int offset) {
        return inspect(bytes, search, offset, 16);
    }

    /** Complete declaration page for explicitly selected Coder classes; still no bodies, constants or initialization. */
    public static Summary inspectAll(byte[] bytes) {
        var result=inspect(bytes,"",0,4097);
        if(result.total()>4096)throw new IllegalStateException("NATIVE_CODER_SYMBOL_LIMIT");
        return result;
    }

    private static Summary inspect(byte[] bytes,String search,int offset,int maximum) {
        if (bytes == null || bytes.length > 8 * 1024 * 1024 || search == null || search.length() > 128
                || offset < 0 || offset > 131070 || maximum<1 || maximum>4097) throw new IllegalArgumentException("NATIVE_API_QUERY");
        var model = ClassFile.of().parse(bytes);
        var candidates = new ArrayList<Candidate>();
        // Retain models while sorting. Only resolve generic signatures for the visible member page.
        for (var field : model.fields()) {
            String name = field.fieldName().stringValue();
            if (name.contains(search)) candidates.add(new Candidate("FIELD", name, field.fieldType().stringValue(), field, field.flags().flagsMask()));
        }
        for (var method : model.methods()) {
            String name = method.methodName().stringValue();
            if (name.contains(search)) candidates.add(new Candidate("METHOD", name, method.methodType().stringValue(), method, method.flags().flagsMask()));
        }
        if (offset > candidates.size()) throw new IllegalArgumentException("NATIVE_API_OFFSET");
        candidates.sort(Comparator.comparing(Candidate::kind).thenComparing(Candidate::name).thenComparing(Candidate::descriptor));
        var budget = new TextBudget();
        String name = budget.take(model.thisClass().asInternalName().replace('/', '.'));
        String superclass = budget.take(model.superclass().map(c -> c.asInternalName().replace('/', '.')).orElse(""));
        String generic = budget.take(signature(model));
        var interfaces = new ArrayList<String>();
        for (var type : model.interfaces()) interfaces.add(budget.take(type.asInternalName().replace('/', '.')));
        int end = Math.min(candidates.size(), offset + maximum);
        var members = new ArrayList<Member>();
        for (var candidate : candidates.subList(offset, end)) {
            List<String> exceptions=List.of();
            if(candidate.model() instanceof MethodModel method){var attribute=method.findAttribute(Attributes.exceptions());if(attribute.isPresent()){if(attribute.get().exceptions().size()>128)throw new IllegalStateException("NATIVE_API_SYMBOL_LIMIT");exceptions=attribute.get().exceptions().stream().map(c->budget.take(c.asInternalName().replace('/','.'))).toList();}}
            members.add(new Member(candidate.kind(), budget.take(candidate.name()), budget.take(candidate.descriptor()),
                    budget.take(signature(candidate.model())), candidate.flags(),exceptions));
        }
        return new Summary(name, superclass, List.copyOf(interfaces), model.majorVersion(), model.flags().flagsMask(),
                generic, List.copyOf(members), candidates.size(), offset, end, end < candidates.size());
    }

    /** Bound repeated constant-pool references before Jackson expands the metadata into JSON. */
    private static final class TextBudget {
        private int remaining = 512 * 1024;
        String take(String value) {
            remaining -= value.length();
            if (remaining < 0) throw new IllegalStateException("NATIVE_API_SYMBOL_LIMIT");
            return value;
        }
    }
}
