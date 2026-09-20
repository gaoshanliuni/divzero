package dev.mineagent.runtime.api.ui;
import java.util.*;
public final class ContainerProtocol {
    public static final Set<String> CAPABILITIES=Set.of("container.read","container.act");
    private ContainerProtocol(){}
    public static boolean bound(UiProtocol.Binding b){return b!=null&&!b.preview()&&!b.targetObjectId().isBlank()&&b.capabilities().equals(CAPABILITIES);}
    public record Item(String id,String name,int count,int maximum,String fingerprint){
        public Item{if(id==null||id.length()>256||name==null||name.length()>512||count<0||maximum<0||fingerprint==null||fingerprint.length()>64)throw new IllegalArgumentException("CONTAINER_ITEM");}
        public static Item empty(){return new Item("","",0,0,"");}
    }
    public record Slot(int index,int x,int y,String group,boolean active,boolean mayPickup,Item item){}
    public record Raw(int menuId,int nativeStateId,String menuType,String actorId,List<Slot> slots,Item carried,String digest){
        public Raw{slots=List.copyOf(slots);if(slots.size()>128||menuType==null||menuType.length()>256||actorId==null||digest==null)throw new IllegalArgumentException("CONTAINER_BUDGET");}
    }
    public record State(long revision,int menuId,int nativeStateId,String menuType,String actorId,List<Slot> slots,Item carried){
        public State{slots=List.copyOf(slots);}
    }
    public record Action(String kind,int slot,int button,String clickType,List<Integer> slots){
        public Action{
            slots=List.copyOf(slots);
            if(!Set.of("CLICK","DRAG").contains(kind)||slot< -999||slot>127||button<0||button>40||slots.size()>128||slots.stream().anyMatch(i->i<0||i>127)||slots.stream().distinct().count()!=slots.size())throw new IllegalArgumentException("CONTAINER_ACTION");
            if(kind.equals("CLICK")&&(!slots.isEmpty()||!Set.of("PICKUP","QUICK_MOVE","SWAP","CLONE","THROW","PICKUP_ALL").contains(clickType)))throw new IllegalArgumentException("CONTAINER_CLICK_TYPE");
            if(kind.equals("DRAG")&&(!clickType.equals("QUICK_CRAFT")||button>2||slots.isEmpty()))throw new IllegalArgumentException("CONTAINER_DRAG");
        }
    }
    public record Result(String executionMode,boolean changed,State state){}
}
