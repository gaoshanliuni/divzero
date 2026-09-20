package dev.mineagent.runtime.agent.ui;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/** Literal page-only postcondition after real DOM mutations; never a world/business verifier. */
public final class UiPageVerification {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final String expected;private String document;private int applied;
    public UiPageVerification(String expected){if(expected==null||expected.isBlank()||expected.length()>256)throw new IllegalArgumentException("UI_PAGE_EXPECTATION");this.expected=normalize(expected);}
    public void action(String action,String receipt){
        try{var a=JSON.readTree(action);var r=JSON.readTree(receipt);
            if(Set.of("click","clickAt","fill","select","toggle","key","drag").contains(a.path("action").asText())&&r.path("status").asText().equals("APPLIED_DOM")
                    &&Set.of("DOM","DOM_COORDINATE_EVENTS").contains(r.path("executionMode").asText()))applied++;
        }catch(Exception invalid){throw new IllegalArgumentException("UI_PAGE_RECEIPT");}
    }
    public boolean matches(String observation){
        try{if(observation==null||observation.length()>65536)throw new IllegalArgumentException("UI_PAGE_OBSERVATION");var root=JSON.readTree(observation);
            if(!root.path("status").asText().equals("OBSERVED")||!root.path("documentId").isTextual()||!root.path("visibleText").isTextual())return false;
            String current=root.path("documentId").asText();if(current.isBlank())return false;
            boolean matched=normalize(root.path("visibleText").asText()).contains(expected);
            if(document==null){document=current;if(matched)throw new IllegalStateException("PAGE_EXPECTATION_ALREADY_PRESENT");return false;}
            if(!document.equals(current))throw new IllegalStateException("STALE_VIEW");return applied>0&&matched;
        }catch(IllegalStateException|IllegalArgumentException e){throw e;}catch(Exception e){return false;}
    }
    public int appliedActions(){return applied;}
    private static String normalize(String value){return value.strip().replaceAll("\\s+"," ");}
}
