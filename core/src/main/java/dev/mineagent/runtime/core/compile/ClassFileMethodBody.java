package dev.mineagent.runtime.core.compile;

import java.lang.classfile.*;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.*;
import java.lang.constant.MethodTypeDesc;
import java.util.*;

/** Bounded, normalized bytecode view for one exact method. It never loads or initializes the class. */
public final class ClassFileMethodBody {
    private ClassFileMethodBody() {}
    public record Step(int bci,int line,String opcode,Map<String,String> operands){public Step{operands=Map.copyOf(operands);}}
    public record Local(int slot,String name,String descriptor,String signature,int startBci,int endBci){}
    public record Handler(int startBci,int endBci,int handlerBci,String catchType){}
    public record Summary(String className,String method,String descriptor,int flags,String implementationKind,
            int codeBytes,int maxStack,int maxLocals,List<Step> instructions,List<Local> locals,List<Handler> handlers,
            int total,int offset,int nextOffset,boolean more){public Summary{instructions=List.copyOf(instructions);locals=List.copyOf(locals);handlers=List.copyOf(handlers);}}

    public static Summary inspect(byte[] bytes,String method,String descriptor,int offset){return inspect(bytes,method,descriptor,offset,64);}
    public static Summary inspectAll(byte[] bytes,String method,String descriptor){return inspect(bytes,method,descriptor,0,4097);}

    private static Summary inspect(byte[] bytes,String method,String descriptor,int offset,int maximum){
        if(bytes==null||bytes.length>8*1024*1024||method==null||method.isBlank()||method.length()>512||descriptor==null||descriptor.length()>2048||offset<0||offset>131070||maximum<1||maximum>4097)throw new IllegalArgumentException("NATIVE_API_METHOD_BODY_QUERY");
        try{MethodTypeDesc.ofDescriptor(descriptor);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("NATIVE_API_METHOD_DESCRIPTOR",invalid);}
        var model=ClassFile.of().parse(bytes);var matches=model.methods().stream().filter(m->m.methodName().equalsString(method)&&m.methodType().equalsString(descriptor)).toList();
        if(matches.size()!=1)throw new IllegalArgumentException("NATIVE_API_METHOD_NOT_FOUND");var selected=matches.getFirst();var body=selected.code();String className=model.thisClass().asInternalName().replace('/','.');
        if(body.isEmpty()){
            if(offset!=0)throw new IllegalArgumentException("NATIVE_API_OFFSET");
            return new Summary(className,method,descriptor,selected.flags().flagsMask(),"ABSTRACT_OR_NATIVE",0,0,0,List.of(),List.of(),List.of(),0,0,0,false);
        }
        var code=body.get();if(!(code instanceof CodeAttribute attribute))throw new IllegalStateException("NATIVE_API_METHOD_CODE_UNAVAILABLE");var elements=code.elementList();if(elements.size()>20000)throw new IllegalStateException("NATIVE_API_METHOD_ELEMENT_LIMIT");
        var labels=new IdentityHashMap<Label,Integer>();int bci=0,rawInstructions=0;
        for(var element:elements){if(element instanceof LabelTarget target)labels.put(target.label(),bci);else if(element instanceof Instruction instruction){rawInstructions++;bci+=instruction.sizeInBytes();}}
        if(rawInstructions>4096)throw new IllegalStateException("NATIVE_API_METHOD_INSTRUCTION_LIMIT");if(offset>rawInstructions)throw new IllegalArgumentException("NATIVE_API_OFFSET");
        var budget=new TextBudget();var all=new ArrayList<Step>(rawInstructions);var locals=new ArrayList<Local>();int line=-1;bci=0;
        for(var element:elements){
            if(element instanceof LineNumber value){line=value.line();continue;}
            if(element instanceof LocalVariable local){if(locals.size()>=4096)throw new IllegalStateException("NATIVE_API_METHOD_LOCAL_LIMIT");locals.add(new Local(local.slot(),budget.take(local.name().stringValue()),budget.take(local.type().stringValue()),"",label(labels,local.startScope()),label(labels,local.endScope())));continue;}
            if(element instanceof LocalVariableType local){if(locals.size()>=4096)throw new IllegalStateException("NATIVE_API_METHOD_LOCAL_LIMIT");locals.add(new Local(local.slot(),budget.take(local.name().stringValue()),"",budget.take(local.signature().stringValue()),label(labels,local.startScope()),label(labels,local.endScope())));continue;}
            if(!(element instanceof Instruction instruction))continue;
            all.add(new Step(bci,line,instruction.opcode().name(),operands(instruction,labels,budget)));bci+=instruction.sizeInBytes();
        }
        var handlers=new ArrayList<Handler>();
        for(var handler:code.exceptionHandlers()){if(handlers.size()>=1024)throw new IllegalStateException("NATIVE_API_METHOD_HANDLER_LIMIT");handlers.add(new Handler(label(labels,handler.tryStart()),label(labels,handler.tryEnd()),label(labels,handler.handler()),handler.catchType().map(v->budget.take(v.asSymbol().descriptorString())).orElse("*")));}
        int end=Math.min(all.size(),offset+maximum);return new Summary(className,method,descriptor,selected.flags().flagsMask(),"NORMALIZED_BYTECODE_NO_EXECUTION",attribute.codeLength(),attribute.maxStack(),attribute.maxLocals(),List.copyOf(all.subList(offset,end)),locals,handlers,all.size(),offset,end,end<all.size());
    }
    private static int label(IdentityHashMap<Label,Integer> labels,Label label){var value=labels.get(label);if(value==null)throw new IllegalStateException("NATIVE_API_METHOD_LABEL");return value;}
    private static Map<String,String> operands(Instruction instruction,IdentityHashMap<Label,Integer> labels,TextBudget budget){
        var result=new LinkedHashMap<String,String>();
        if(instruction instanceof InvokeInstruction value){result.put("owner",budget.take(value.owner().asSymbol().descriptorString()));result.put("name",budget.take(value.name().stringValue()));result.put("descriptor",budget.take(value.type().stringValue()));result.put("interface",Boolean.toString(value.isInterface()));}
        else if(instruction instanceof InvokeDynamicInstruction value){result.put("name",budget.take(value.name().stringValue()));result.put("descriptor",budget.take(value.type().stringValue()));result.put("bootstrap",budget.take(value.bootstrapMethod().toString()));result.put("bootstrapArgs",budget.take(value.bootstrapArgs().toString()));}
        else if(instruction instanceof FieldInstruction value){result.put("owner",budget.take(value.owner().asSymbol().descriptorString()));result.put("name",budget.take(value.name().stringValue()));result.put("descriptor",budget.take(value.type().stringValue()));}
        else if(instruction instanceof ConstantInstruction value){result.put("type",value.typeKind().name());result.put("value",budget.take(value.constantValue().toString()));}
        else if(instruction instanceof TypeCheckInstruction value)result.put("type",budget.take(value.type().asSymbol().descriptorString()));
        else if(instruction instanceof NewObjectInstruction value)result.put("type",budget.take(value.className().asSymbol().descriptorString()));
        else if(instruction instanceof NewReferenceArrayInstruction value)result.put("component",budget.take(value.componentType().asSymbol().descriptorString()));
        else if(instruction instanceof NewPrimitiveArrayInstruction value)result.put("component",value.typeKind().name());
        else if(instruction instanceof NewMultiArrayInstruction value){result.put("type",budget.take(value.arrayType().asSymbol().descriptorString()));result.put("dimensions",Integer.toString(value.dimensions()));}
        else if(instruction instanceof LoadInstruction value)result.put("slot",Integer.toString(value.slot()));
        else if(instruction instanceof StoreInstruction value)result.put("slot",Integer.toString(value.slot()));
        else if(instruction instanceof IncrementInstruction value){result.put("slot",Integer.toString(value.slot()));result.put("constant",Integer.toString(value.constant()));}
        else if(instruction instanceof BranchInstruction value)result.put("targetBci",Integer.toString(label(labels,value.target())));
        else if(instruction instanceof LookupSwitchInstruction value){result.put("defaultBci",Integer.toString(label(labels,value.defaultTarget())));result.put("cases",budget.take(value.cases().stream().map(c->c.caseValue()+":"+label(labels,c.target())).toList().toString()));}
        else if(instruction instanceof TableSwitchInstruction value){result.put("low",Integer.toString(value.lowValue()));result.put("high",Integer.toString(value.highValue()));result.put("defaultBci",Integer.toString(label(labels,value.defaultTarget())));result.put("targets",budget.take(value.cases().stream().map(c->Integer.toString(label(labels,c.target()))).toList().toString()));}
        return Map.copyOf(result);
    }
    private static final class TextBudget {private int remaining=512*1024;String take(String value){if(value==null||value.length()>65536||(remaining-=value.length())<0)throw new IllegalStateException("NATIVE_API_METHOD_TEXT_LIMIT");return value;}}
}
