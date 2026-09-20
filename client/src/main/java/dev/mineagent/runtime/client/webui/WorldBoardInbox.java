package dev.mineagent.runtime.client.webui;
import dev.mineagent.runtime.api.scoreboard.WorldBoardFrame;
import java.util.*;
/** Called only after the transport connection itself has been verified by the native payload handler. */
public final class WorldBoardInbox {
    private Object connection;private UUID server,world;private long sequence;
    public boolean accept(Object peer,UUID viewer,String dimension,WorldBoardFrame frame){
        if(peer==null||!frame.viewerId().equals(viewer)||!frame.dimension().equals(dimension))return false;
        if(connection!=peer){connection=peer;server=frame.serverInstanceId();world=frame.worldId();sequence=0;}
        if(!server.equals(frame.serverInstanceId())||!world.equals(frame.worldId())||frame.sequence()<=sequence)return false;
        sequence=frame.sequence();return true;
    }
    public void clear(){connection=null;server=null;world=null;sequence=0;}
}
