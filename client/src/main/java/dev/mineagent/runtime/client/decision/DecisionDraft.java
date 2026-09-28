package dev.mineagent.runtime.client.decision;

import dev.mineagent.runtime.api.decision.*;
import java.util.*;

/** Data-only player draft. Restoring it never submits an answer or grants authority. */
public final class DecisionDraft {
    public record Saved(UUID decisionId,long revision,List<String> selected,String customText,UUID submissionId) {}
    private DecisionRequest request;
    private final Set<String> selected=new TreeSet<>();
    private String customText="";
    private UUID submissionId;
    private boolean edited;
    public DecisionDraft(DecisionRequest request){this.request=Objects.requireNonNull(request);}
    public DecisionRequest request(){return request;}
    public Set<String> selected(){return Set.copyOf(selected);}
    public String customText(){return customText;}
    public boolean update(DecisionRequest next){
        if(!request.decisionId().equals(next.decisionId()))throw new IllegalArgumentException("DECISION_ID_MISMATCH");
        if(next.revision()<request.revision())return false;
        if(next.revision()!=request.revision()){
            // Several revisions may pass while the card is hidden.
            boolean sameQuestion=new DecisionRequest(request.decisionId(),next.revision(),request.recipientPlayerId(),request.taskRevision(),request.kind(),request.title(),request.question(),request.options(),request.selectionMode(),request.minSelections(),request.maxSelections(),request.allowCustomInput(),next.status()).equals(next);
            boolean suspension=Set.of(DecisionStatus.OPEN,DecisionStatus.DEFERRED).contains(request.status())&&Set.of(DecisionStatus.OPEN,DecisionStatus.DEFERRED).contains(next.status());
            boolean invalidated=Set.of(DecisionStatus.CANCELLED,DecisionStatus.SUPERSEDED,DecisionStatus.EXPIRED).contains(next.status());
            submissionId=null;
            if(!sameQuestion||!suspension&&!invalidated){selected.clear();customText="";edited=false;}
        }
        request=next;return true;
    }
    private void writable(){if(request.status()!=DecisionStatus.OPEN)throw new IllegalStateException("DECISION_NOT_OPEN");}
    public void toggle(String id){
        writable();if(request.options().stream().noneMatch(o->o.optionId().equals(id)))throw new IllegalArgumentException("UNKNOWN_OPTION");
        if(!selected.remove(id)){if(request.selectionMode()==SelectionMode.SINGLE)selected.clear();selected.add(id);}submissionId=null;edited=true;
    }
    public void clear(){writable();selected.clear();submissionId=null;edited=true;}
    public void edit(String value){
        writable();if(!request.allowCustomInput()||value==null||value.length()>8192)throw new IllegalArgumentException("CUSTOM_INPUT_NOT_ALLOWED");
        if(!value.equals(customText))submissionId=null;customText=value;edited=true;
    }
    public Saved snapshot(){return new Saved(request.decisionId(),request.revision(),List.copyOf(selected),customText,submissionId);}
    public void restore(Saved saved){
        if(edited||saved==null||!request.decisionId().equals(saved.decisionId())||request.revision()!=saved.revision())return;
        if(saved.selected()==null||saved.customText()==null||saved.customText().length()>8192)return;
        var ids=new HashSet<String>();request.options().forEach(o->ids.add(o.optionId()));
        if(!ids.containsAll(saved.selected())||saved.selected().size()>64)return;
        selected.clear();selected.addAll(saved.selected());customText=request.allowCustomInput()?saved.customText():"";submissionId=saved.submissionId();
    }
    public void restoreAccepted(DecisionAnswerSubmission answer){
        if(request.status()!=DecisionStatus.RESOLVED||!request.decisionId().equals(answer.decisionId())||answer.expectedRevision()+1!=request.revision())throw new IllegalArgumentException("STALE_ACCEPTED_ANSWER");
        var ids=new HashSet<String>();request.options().forEach(o->ids.add(o.optionId()));if(!ids.containsAll(answer.selectedOptionIds()))throw new IllegalArgumentException("UNKNOWN_OPTION");
        selected.clear();selected.addAll(answer.selectedOptionIds());customText=answer.customText();submissionId=answer.submissionId();edited=true;
    }
    public DecisionAnswerSubmission submission(){
        writable();int count=selected.size();
        if(request.kind()==DecisionKind.AUTHORIZATION&&count<Math.max(1,request.minSelections()))throw new IllegalArgumentException("AUTHORIZATION_CHOICE_REQUIRED");
        if(count>request.maxSelections()||count<request.minSelections()&&customText.isBlank()||count==0&&customText.isBlank())throw new IllegalArgumentException("INVALID_SELECTION_COUNT");
        if(submissionId==null)submissionId=UUID.randomUUID();
        return new DecisionAnswerSubmission(request.decisionId(),request.revision(),submissionId,List.copyOf(selected),customText,AnswerSource.UI);
    }
}
