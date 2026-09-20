package dev.mineagent.runtime.api.ui;

import java.util.*;

/** Native-only ledger routing. An identifier is never evidence of Session authorization. */
public record StatePushToken(boolean schedule,UUID id){
    public StatePushToken{Objects.requireNonNull(id);}
    public static StatePushToken parse(String wire){
        if(wire==null||wire.length()>45)throw new IllegalArgumentException("STATE_PUSH_TOKEN");boolean schedule=wire.startsWith("schedule:");String value=schedule?wire.substring(9):wire;UUID id=UUID.fromString(value);
        if(!id.toString().equals(value))throw new IllegalArgumentException("STATE_PUSH_TOKEN_CANONICAL");return new StatePushToken(schedule,id);
    }
    public String wire(){return schedule?"schedule:"+id:id.toString();}
}
