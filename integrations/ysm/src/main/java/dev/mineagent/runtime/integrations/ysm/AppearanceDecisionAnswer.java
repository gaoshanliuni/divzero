package dev.mineagent.runtime.integrations.ysm;
import java.util.*;
/** Explicit appearance directives merge with a selection. Unrecognized suggestions are never silently discarded. */
public final class AppearanceDecisionAnswer {
 private AppearanceDecisionAnswer(){}
 public static Optional<AppearanceIntentParser.Selection> resolve(AppearanceIntentParser.Selection base,String text){
  if(text==null||text.length()>1024)return Optional.empty();String input=text.strip().replaceFirst("^(?:但|不过|同时|并且)\\s*","");
  if(input.isBlank())return base==null?Optional.empty():AppearanceIntentParser.checkedSelection(base.modelId(),base.textureId(),base.animationId());
  if(input.contains("|"))return AppearanceIntentParser.parse(input);
  var values=new HashMap<String,String>();
  for(String token:input.split("\\s+")){
   int split=token.indexOf('=');if(split<1)return Optional.empty();String key=switch(token.substring(0,split).toLowerCase(Locale.ROOT)){case "模型","model"->"model";case "贴图","texture"->"texture";case "动画","animation"->"animation";default->"";};
   String value=token.substring(split+1);if(key.isEmpty()||values.putIfAbsent(key,value)!=null)return Optional.empty();
  }
  String model=values.getOrDefault("model",base==null?"":base.modelId());if(model.isBlank())return Optional.empty();
  return AppearanceIntentParser.checkedSelection(model,values.getOrDefault("texture",base==null?"":base.textureId()),values.getOrDefault("animation",base==null?"":base.animationId()));
 }
}
