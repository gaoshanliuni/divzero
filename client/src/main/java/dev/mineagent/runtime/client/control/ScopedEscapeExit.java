package dev.mineagent.runtime.client.control;

/** Two released Esc presses in the game surface; menus and focus changes break the gesture. */
public final class ScopedEscapeExit {
    public enum Result { PASS, CONSUME, ARMED, EXIT }
    private boolean held,armed;
    private long firstPress;

    public Result handle(boolean active,boolean gameSurface,boolean sameWindow,int action,long nowMillis){
        if(!active||!gameSurface||!sameWindow){reset();return Result.PASS;}
        if(action==0){held=false;return Result.CONSUME;}
        if(action!=1||held)return Result.CONSUME;
        held=true;long elapsed=nowMillis-firstPress;
        if(armed&&elapsed>=0&&elapsed<=600){reset();return Result.EXIT;}
        armed=true;firstPress=nowMillis;return Result.ARMED;
    }
    public void reset(){held=false;armed=false;firstPress=0;}
}
