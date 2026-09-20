package dev.mineagent.runtime.scripting;

import dev.latvian.mods.rhino.*;
import java.util.*;

/** Copies data between script realms. No Functions, Java wrappers or live Scriptable references escape. */
final class ScriptExchange {
    private ScriptExchange(){}
    private static final Object UNDEFINED=new Object();
    private static final int MAX_VALUES=4096,MAX_BYTES=65536,MAX_DEPTH=16;
    private static final class Budget {
        int values,bytes;
        final Scriptable root;
        Budget(Scriptable root){this.root=root;}
        final Set<Object> visiting=Collections.newSetFromMap(new IdentityHashMap<>());
        void value(int depth){if(++values>MAX_VALUES||depth>MAX_DEPTH)throw error();}
        String text(String text){if(text.length()>MAX_BYTES)throw error();bytes+=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes>MAX_BYTES)throw error();return text;}
    }
    static IllegalArgumentException error(){return new IllegalArgumentException("STUDIO_SCRIPT_DEPENDENCY_DATA_LIMIT_OR_TYPE");}
    static List<Object> arguments(Context cx,Scriptable root,Object value){
        if(!(value instanceof NativeArray array)||array.getLength()>32)throw error();
        @SuppressWarnings("unchecked") var result=(List<Object>)copy(cx,array,new Budget(root),0);return result;
    }
    static List<Object> portableArguments(Context cx,Scriptable root,Object value){@SuppressWarnings("unchecked")var result=(List<Object>)portable(arguments(cx,root,value));return result;}
    static Object portable(Context cx,Scriptable root,Object value){return portable(copy(cx,root,value));}
    private static Object portable(Object value){if(value==UNDEFINED)return null;if(value instanceof List<?> list){var result=new ArrayList<Object>(list.size());for(var item:list)result.add(portable(item));return result;}if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();for(var entry:map.entrySet())result.put((String)entry.getKey(),portable(entry.getValue()));return result;}return value;}
    static Object copy(Context cx,Scriptable root,Object value){return copy(cx,value,new Budget(root),0);}
    static boolean belongs(Scriptable value,Scriptable root){
        var visited=Collections.newSetFromMap(new IdentityHashMap<Scriptable,Boolean>());
        for(var current=value;current!=null&&visited.size()<256&&visited.add(current);current=current.getParentScope())if(current==root)return true;return false;
    }
    private static Object copy(Context cx,Object value,Budget budget,int depth){
        budget.value(depth);
        if(Undefined.isUndefined(value))return UNDEFINED;
        if(value==null||value instanceof Boolean)return value;
        if(value instanceof String s)return budget.text(s);
        if(value instanceof Number number){double d=number.doubleValue();if(!Double.isFinite(d)||number instanceof java.math.BigDecimal||number instanceof java.math.BigInteger||number instanceof Long&&Math.abs(d)>9007199254740991D)throw error();return d;}
        if(value instanceof Wrapper||value instanceof Function||!(value instanceof NativeObject||value instanceof NativeArray))throw error();
        if(!belongs((Scriptable)value,budget.root)||!budget.visiting.add(value))throw error();
        try{
            if(value instanceof NativeArray array){
                if(array.getLength()>MAX_VALUES)throw error();var result=new ArrayList<Object>();
                for(int i=0;i<array.getLength();i++)result.add(copy(cx,array.get(cx,i,array),budget,depth+1));return result;
            }
            var object=(NativeObject)value;var ids=object.getIds(cx);if(ids.length>MAX_VALUES)throw error();var result=new LinkedHashMap<String,Object>();
            for(Object id:ids){
                String key;if(id instanceof String s)key=s;else if(id instanceof Integer n)key=n.toString();else throw error();
                if(key.length()>128||Set.of("__proto__","prototype","constructor").contains(key))throw error();budget.text(key);
                if(result.containsKey(key))throw error();result.put(key,copy(cx,id instanceof Integer n?object.get(cx,n,object):object.get(cx,key,object),budget,depth+1));
            }
            return result;
        }finally{budget.visiting.remove(value);}
    }
    static Object into(Context cx,Scriptable scope,Object value){
        if(value==UNDEFINED)return Context.getUndefinedValue();
        if(value instanceof List<?> list){Object[] result=new Object[list.size()];for(int i=0;i<result.length;i++)result[i]=into(cx,scope,list.get(i));return cx.newArray(scope,result);}
        if(value instanceof Map<?,?> map){var result=cx.newObject(scope);for(var entry:map.entrySet())result.put(cx,(String)entry.getKey(),result,into(cx,scope,entry.getValue()));return result;}
        return value;
    }
}
