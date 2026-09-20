package dev.mineagent.runtime.core.scoreboard;
import dev.mineagent.runtime.api.scoreboard.*;
import java.util.*;
/** Generic text projection of real ScoreView rows. Audience filtering precedes serialization and budgeting. */
public final class WorldBoardProjection {
    public boolean eligible(ScoreView v,ScoreAudienceContext viewer,String dimension,double x,double y,double z){
        return v.enabled()&&v.kind()==ScoreViewKind.WORLD_BOARD&&v.target().target().equals("WORLD")&&v.target().dimension().equals(dimension)
                &&new ScoreboardAudienceResolver().visible(v.audience(),viewer)&&range(v)>0&&distance(v.target(),x,y,z)<=range(v)*range(v);
    }
    private static int range(ScoreView v){try{int value=Integer.parseInt(v.layout().getOrDefault("viewDistance","128"));return value>=1&&value<=128?value:0;}catch(NumberFormatException invalid){return 0;}}
    public List<WorldBoardFrame.Board> project(List<ScoreView> views,Map<UUID,ScoreSourceBinding> sources,ScoreboardSnapshot data,
                                             ScoreAudienceContext viewer,String dimension,double x,double y,double z){
        var eligible=views.stream().filter(v->eligible(v,viewer,dimension,x,y,z)).sorted(Comparator.comparingDouble((ScoreView v)->distance(v.target(),x,y,z)).thenComparing(v->v.viewId().toString())).toList();
        var result=new ArrayList<WorldBoardFrame.Board>();int remaining=8192;
        for(var v:eligible){
            if(result.size()==16||remaining<64)break;
            var source=sources.get(v.sourceId());if(source==null)continue;
            ScoreViewSnapshot projected;
            try{projected=new ScoreboardViewProjector().project(v,source,data,v.revision());}catch(IllegalArgumentException missing){continue;}
            String text=format(projected,Math.min(remaining,4096));var t=v.target();
            try{result.add(new WorldBoardFrame.Board(v.viewId(),v.ownerPackageId(),v.revision(),t.x(),t.y(),t.z(),t.yaw(),t.scale(),text));remaining-=text.length();}
            catch(IllegalArgumentException legacyInvalid){/* Invalid legacy coordinates do not become render commands. */}
        }
        return List.copyOf(result);
    }
    private static double distance(ScoreViewTarget t,double x,double y,double z){return (t.x()-x)*(t.x()-x)+(t.y()-y)*(t.y()-y)+(t.z()-z)*(t.z()-z);}
    private static String format(ScoreViewSnapshot snapshot,int limit){
        StringBuilder text=new StringBuilder(snapshot.title());int n=0;
        for(var row:snapshot.rows()){
            String line="\n"+(++n)+". "+row.displayName()+"  "+row.formattedScore();
            if(text.length()+line.length()>limit-24){text.append("\n… 内容达到显示预算");break;}text.append(line);
        }
        if(snapshot.rows().isEmpty())text.append("\n暂无条目");
        if(text.length()>limit){int end=limit-2;if(end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end)+"…";}
        return text.toString();
    }
}
