package dev.mineagent.runtime.api.packages;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deep-copies the language-neutral CLIENT package call format. */
public final class ClientPackageData {
    private static final int MAX_ARGUMENTS=32,MAX_VALUES=4096,MAX_BYTES=65536,MAX_DEPTH=16;
    private ClientPackageData(){}
    public static List<Object> arguments(List<?> value){if(value==null||value.size()>MAX_ARGUMENTS)throw error();@SuppressWarnings("unchecked")var result=(List<Object>)copy(value,new Budget(),0);return result;}
    public static Object copy(Object value){return copy(value,new Budget(),0);}
    private static Object copy(Object value,Budget budget,int depth){budget.value(depth);if(value==null||value instanceof Boolean)return value;if(value instanceof String text)return budget.text(text);if(value instanceof Number number){double result=number.doubleValue();if(!Double.isFinite(result)||number instanceof java.math.BigDecimal||number instanceof java.math.BigInteger||number instanceof Long&&Math.abs(result)>9007199254740991D)throw error();return result;}if(!(value instanceof List<?>||value instanceof Map<?,?>)||!budget.visiting.add(value))throw error();try{if(value instanceof List<?> list){if(list.size()>MAX_VALUES)throw error();var result=new ArrayList<Object>(list.size());for(var item:list)result.add(copy(item,budget,depth+1));return Collections.unmodifiableList(result);}var map=(Map<?,?>)value;if(map.size()>MAX_VALUES)throw error();var result=new LinkedHashMap<String,Object>();for(var entry:map.entrySet()){if(!(entry.getKey() instanceof String key)||key.length()>128||Set.of("__proto__","prototype","constructor").contains(key)||result.containsKey(key))throw error();budget.text(key);result.put(key,copy(entry.getValue(),budget,depth+1));}return Collections.unmodifiableMap(result);}finally{budget.visiting.remove(value);}}
    private static IllegalArgumentException error(){return new IllegalArgumentException("CLIENT_PACKAGE_DATA_LIMIT_OR_TYPE");}
    private static final class Budget {int values;long bytes;final Set<Object> visiting=Collections.newSetFromMap(new IdentityHashMap<>());void value(int depth){if(++values>MAX_VALUES||depth>MAX_DEPTH)throw error();}String text(String value){bytes+=value.getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_BYTES)throw error();return value;}}
}
