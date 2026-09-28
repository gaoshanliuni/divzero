package dev.mineagent.runtime.core.geometry;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Mobile clearing head, constrained to an explicit world region. Hollow building generation never uses it implicitly. */
public record BulldozerSpec(String id,String target,String dimension,List<Integer> min,List<Integer> max,
                            int width,int height,int depth,boolean drops,boolean blockEntities,boolean unbreakable) {
    public BulldozerSpec {
        if(id==null||!id.matches("[A-Za-z][A-Za-z0-9_-]{0,95}"))throw bad("ID");
        if(!Set.of("$viewer","$agent").contains(target))UUID.fromString(target);
        if(dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw bad("DIMENSION");
        min=List.copyOf(min);max=List.copyOf(max);if(min.size()!=3||max.size()!=3)throw bad("BOUNDS");
        for(int i=0;i<3;i++)if(min.get(i)>max.get(i)||(long)max.get(i)-min.get(i)>=2048||Math.abs((long)min.get(i))>30_000_000||Math.abs((long)max.get(i))>30_000_000)throw bad("BOUNDS");
        if(width<1||width>31||height<1||height>32||depth<1||depth>32)throw bad("HEAD_SIZE");
    }
    public static BulldozerSpec parse(JsonNode a){return new BulldozerSpec(a.path("id").asText(),a.path("target").asText("$viewer"),a.path("dimension").asText(),point(a.path("min")),point(a.path("max")),integer(a,"width",1,31),integer(a,"height",1,32),integer(a,"depth",1,32),flag(a,"drop_items",true),flag(a,"clear_block_entities",false),flag(a,"clear_unbreakable",false));}
    private static boolean flag(JsonNode a,String key,boolean fallback){if(!a.has(key))return fallback;if(!a.get(key).isBoolean())throw bad("BOOLEAN");return a.get(key).asBoolean();}
    private static List<Integer> point(JsonNode a){if(!a.isArray()||a.size()!=3)throw bad("BOUNDS");var values=new ArrayList<Integer>();for(var value:a){if(!value.isIntegralNumber()||!value.canConvertToInt())throw bad("BOUNDS");values.add(value.intValue());}return values;}
    private static int integer(JsonNode a,String key,int min,int max){var n=a.path(key);if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<min||n.intValue()>max)throw bad("HEAD_SIZE");return n.intValue();}
    public boolean contains(int x,int y,int z){return x>=min.get(0)&&x<=max.get(0)&&y>=min.get(1)&&y<=max.get(1)&&z>=min.get(2)&&z<=max.get(2);}
    public int volume(){return width*height*depth;}
    public static int heading(float yaw){if(!Float.isFinite(yaw))throw bad("YAW");return Math.floorMod(Math.round(yaw/90),4);}
    public int[] cell(int x,int y,int z,int heading,int index){if(index<0||index>=volume()||heading<0||heading>3)throw bad("CURSOR");int forward=index%depth,up=index/depth%height,side=index/(depth*height)-width/2;int dx=new int[]{0,-1,0,1}[heading],dz=new int[]{1,0,-1,0}[heading];return new int[]{x+dx*forward-dz*side,y+up,z+dz*forward+dx*side};}
    private static IllegalArgumentException bad(String code){return new IllegalArgumentException("BULLDOZER_"+code);}
}
