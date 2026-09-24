package dev.mineagent.runtime.client.control;
import com.fasterxml.jackson.databind.ObjectMapper;import java.util.*;
/** Only authored UI strings enter this lookup. Never translate arbitrary player/model text. */
public final class UiLanguageCatalog {
 private static final Map<String,String> ENGLISH=load();
 private static Map<String,String> load(){try(var in=UiLanguageCatalog.class.getResourceAsStream("/assets/mineagent_runtime/i18n/en_us.json")){if(in==null)throw new IllegalStateException("UI_LANGUAGE_CATALOG_MISSING");return Collections.unmodifiableMap(new ObjectMapper().readValue(in,new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}));}catch(Exception e){throw new IllegalStateException("UI_LANGUAGE_CATALOG_INVALID",e);}}
 public static String text(String language,String source){return "en_us".equals(language)?ENGLISH.getOrDefault(source,source):source;}
 public static Map<String,String> english(){return ENGLISH;}
 private UiLanguageCatalog(){}
}
