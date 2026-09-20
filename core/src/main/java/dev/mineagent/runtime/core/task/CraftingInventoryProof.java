package dev.mineagent.runtime.core.task;
import java.util.*;
/** Predict only transfer of the real native result and real native remainder slots, never manufacture items. */
public final class CraftingInventoryProof {
    private CraftingInventoryProof(){}
    public static Map<String,Integer> expected(Map<String,Integer> beforeTake,String result,int count,Map<String,Integer> remainders){
        if(result==null||result.isBlank()||count<1)throw new IllegalArgumentException("CRAFT_RESULT");
        var expected=new TreeMap<>(beforeTake);expected.merge(result,count,Math::addExact);remainders.forEach((k,v)->expected.merge(k,v,Math::addExact));return Map.copyOf(expected);
    }
    public static boolean matches(Map<String,Integer> expected,Map<String,Integer> actual){return expected.equals(actual);}
}
