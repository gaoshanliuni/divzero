package dev.mineagent.runtime.core.ui;
import dev.mineagent.runtime.api.ui.ContainerProtocol.State;
import java.util.Set;
/** Independent native result predicate; does not trust the page's success message or requested values. */
public record ContainerExpectation(String group,int slot,String itemId,String name,int count,int carriedCount){
    public ContainerExpectation{
        if(!Set.of("player","container").contains(group)||slot< -1||slot>127||itemId==null||!itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||name==null||name.length()>512||count<0||count>8192||carriedCount<0||carriedCount>128)throw new IllegalArgumentException("CONTAINER_EXPECTATION");
    }
    public boolean matches(State actual,String actorId){
        if(!actual.actorId().equals(actorId)||actual.carried().count()!=carriedCount)return false;
        int total=actual.slots().stream().filter(s->s.group().equals(group)&&(slot<0||s.index()==slot)&&s.item().id().equals(itemId)&&(name.isEmpty()||s.item().name().equals(name))).mapToInt(s->s.item().count()).sum();
        return total==count;
    }
    public boolean matches(dev.mineagent.runtime.api.ui.ContainerProtocol.Raw actual,String actorId){return matches(new State(0,actual.menuId(),actual.nativeStateId(),actual.menuType(),actual.actorId(),actual.slots(),actual.carried()),actorId);}
}
