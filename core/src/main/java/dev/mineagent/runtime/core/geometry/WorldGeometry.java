package dev.mineagent.runtime.core.geometry;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Deterministic, data-only voxel geometry. No world access; limits reject, never truncate. */
public final class WorldGeometry {
    public static final int MAX_AXIS=2048;
    /** In-memory convenience only; production uses stream() into a disk spool. */
    public static final int MAX_CELLS=262144;
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public record Pos(int x,int y,int z) {
        public Pos add(Pos q){return new Pos(Math.addExact(x,q.x),Math.addExact(y,q.y),Math.addExact(z,q.z));}
        public List<Integer> list(){return List.of(x,y,z);}
    }
    public record Cell(Pos pos,String state,List<String> orientation,byte[] blockEntity){public Cell(Pos pos,String state,List<String> orientation){this(pos,state,orientation,null);}}
    public record Plan(Pos origin,List<Cell> cells,List<String> replace,int parts){}
    private record Vec(double x,double y,double z) {
        Vec add(Vec v){return new Vec(x+v.x,y+v.y,z+v.z);} Vec mul(double n){return new Vec(x*n,y*n,z*n);}
        double max(){return Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));}
        double length(){return Math.sqrt(x*x+y*y+z*z);}
    }
    public static final String CONTRACT="""
        世界方块几何 v1（不是物品三角网格/网页）。先plan_world_geometry，核对实际计划，apply_world_geometry(plan_id)；无需玩家手输ID。
        source是JSON文本：{"origin":[绝对整数x,y,z],"parts":[...],"replace":[可选方块/完整状态字符串]}。
        parts按顺序叠加，重叠最后一个胜出。所有part坐标相对于origin。replace省略=全部可编辑方块；给出时只改匹配的旧方块（空数组非法）。
        每part有kind、material、可选transforms。material可直接是"minecraft:stone"或完整原生状态如"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"。
        material也可为{"mode":"checker|stripe|layers|weighted","states":[状态...],"axis":"x|y|z","width":1,"seed":0,"weights":[正整数...]}
        checker按x+y+z/width；stripe按axis/width循环；layers按axis/width夹在首尾材料（不循环）；weighted是坐标seed确定性加权混材，weights可省略等权。
        几何kind及仅允许的额外字段：
        box: min/max三整数（两端包含），mode=solid|walls|shell（默认solid），thickness正整数默认1；walls不含顶/底，shell包含。
        line: points至少2个三维点，radius>=0默认0，折线可多点。
        plane: points恰3个三维点，分别为原点、沿U的端点、沿V的端点，填充平行四边形。
        polygon: points为至少3个同Y平面三维点（不重复闭合点），height正整数向上拉伸，非自交多边形。
        slope: min/max三整数，axis=x|z默认x，ascending默认true，thickness=0默认实心坡体，正数为顶部屋面厚度。
        curve: points恰4个三维点（三次Bezier控制点），radius>=0默认0。
        surface: points恰4个三维点，顺序p00,p10,p01,p11，双线性曲面（可非共面）。
        cylinder: center三维点为底层中心，radius=[x半径,z半径]，height正整数，thickness=0默认实心，正数为空心侧壁。
        ellipsoid/dome: center三维点，radius=[x半径,y半径,z半径]，thickness=0默认实心，正数为空壳。dome只保留center.y及以上。
        transforms顺序执行（每步最多32项），作用于局部坐标及方块朝向：
        {"op":"translate","offset":[整数x,y,z]}
        {"op":"rotate","turns":1,"pivot":[整数x,y,z]}绕Y轴顺时针90度，turns=-3..3，pivot默认[0,0,0]。
        {"op":"mirror","axis":"x|z","pivot":[整数x,y,z]}对应坐标取反，同时翻转楼梯/门等原生BlockState。
        {"op":"array","count":[1..2048,1..2048,1..2048],"step":[整数x,y,z]}包含原件，网格阵列。
        {"op":"path","points":[三维点...],"spacing":正数,"orient":true}沿折线路径按弧长重复，包含起点；复制局部模板以[0,0,0]为锚，orient可按切向将模板+Z朝向旋转到最近90度。
        连续曲线/曲面栅格化为方块近似，不是任意角度BlockState旋转。坐标局部±4096；最终包围盒每轴最多2048格（包含两端），即最大2048×2048×2048范围。不再设262144体素总量上限；大计划在后台写入磁盘、按分页/分Tick采样和执行，不全部装内存。磁盘不足、取消、未加载区块或世界高度外明确失败，不截断。工具轮数/累计调用数不限。
        实際世界读取/写入分Tick，仅读写已加载区块且世界边界/高度合法；真实游戏管理权限；禁止覆盖现有BlockEntity或创建BlockEntity，不支持NBT/箱子内容编辑。
        计划绑定玩家/AI/维度/权限，完成采样后10分钟可启动、一次消费；大计划进行中不按这10分钟中断；包含实际旧状态和新状态计数、bounds、预览。apply重新核对旧状态，过期/变化拒绝；执行中变化/停止可能PARTIAL，绝不自动重放。
        回执包含写入计数及等待物理更新后的实际状态匹配数。放置成功不等于红石/流体机器工作；仍需inspect_blocks等待读回。inspect_world_geometry(plan_id,offset)可分页读取计划所有选中体素，offset为返回的持久游标，不能把分页当截断；旧的已完成计划缓存可能被新计划回收，实际写入回执仍在inspect_operations。
        示例：{"origin":[0,80,0],"parts":[{"kind":"box","min":[0,0,0],"max":[5,3,5],"mode":"walls","material":"minecraft:stone_bricks"},{"kind":"cylinder","center":[8,0,0],"radius":[2,2],"height":5,"material":{"mode":"stripe","states":["minecraft:stone","minecraft:andesite"],"axis":"y"}}]}
        """;
    private java.util.function.Consumer<Pos> positionSink;
    private void step(){if(Thread.currentThread().isInterrupted())throw bad("WORK_LIMIT");}
    private static IllegalArgumentException bad(String reason){return new IllegalArgumentException("GEOMETRY_"+reason);}
    private static void keys(JsonNode n,String... names){if(!n.isObject()||!Set.of(names).containsAll(n.properties().stream().map(Map.Entry::getKey).toList()))throw bad("FIELDS");}
    private static double num(JsonNode n,double min,double max){if(!n.isNumber()||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)throw bad("NUMBER");return n.doubleValue();}
    private static int integer(JsonNode n,int min,int max){if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<min||n.intValue()>max)throw bad("INTEGER");return n.intValue();}
    private static int integer(JsonNode n,String key,int fallback,int min,int max){return n.has(key)?integer(n.get(key),min,max):fallback;}
    private static boolean bool(JsonNode n,String key,boolean fallback){if(!n.has(key))return fallback;if(!n.get(key).isBoolean())throw bad("BOOLEAN");return n.get(key).booleanValue();}
    private static String text(JsonNode n){if(!n.isTextual()||n.textValue().isBlank()||n.textValue().length()>1024)throw bad("TEXT");return n.textValue();}
    private static String choice(JsonNode n,String key,String fallback,String... choices){String s=n.has(key)?text(n.get(key)):fallback;if(!List.of(choices).contains(s))throw bad("ENUM");return s;}
    private static Vec vec(JsonNode n){if(!n.isArray()||n.size()!=3)throw bad("VECTOR");return new Vec(num(n.get(0),-4096,4096),num(n.get(1),-4096,4096),num(n.get(2),-4096,4096));}
    private static Pos pos(JsonNode n,int max){if(!n.isArray()||n.size()!=3)throw bad("VECTOR");return new Pos(integer(n.get(0),-max,max),integer(n.get(1),-max,max),integer(n.get(2),-max,max));}
    private static List<Vec> points(JsonNode n,int min,int max){if(!n.isArray()||n.size()<min||n.size()>max)throw bad("POINTS");var result=new ArrayList<Vec>();n.forEach(v->result.add(vec(v)));return result;}
    private static Pos round(Vec v){return new Pos((int)Math.round(v.x),(int)Math.round(v.y),(int)Math.round(v.z));}
    private void emit(Set<Pos> set,Pos p){step();if(Math.abs(p.x)>4096||Math.abs(p.y)>4096||Math.abs(p.z)>4096)throw bad("LOCAL_RANGE");set.add(p);if(set.size()>MAX_CELLS)throw bad("CELL_LIMIT");}
    private void ball(Set<Pos> set,Vec p,double r){int b=(int)Math.ceil(r);Pos c=round(p);for(int x=-b;x<=b;x++)for(int y=-b;y<=b;y++)for(int z=-b;z<=b;z++){step();if(x*x+y*y+z*z<=r*r+1e-8)emit(set,c.add(new Pos(x,y,z)));}}
    private void line(Set<Pos> out,Vec a,Vec b,double radius){int steps=Math.max(1,(int)Math.ceil(b.add(a.mul(-1)).max()*4));for(int i=0;i<=steps;i++){step();ball(out,a.mul(1.0-i/(double)steps).add(b.mul(i/(double)steps)),radius);}}
    private static void bounds(Pos a,Pos b){if(a.x>b.x||a.y>b.y||a.z>b.z)throw bad("BOUNDS");}
    private Set<Pos> shape(JsonNode s){
        String kind=text(s.path("kind"));var allowed=new HashSet<>(Set.of("kind","material","transforms"));
        allowed.addAll(switch(kind){case "box"->Set.of("min","max","mode","thickness");case "slope"->Set.of("min","max","axis","ascending","thickness");case "line","curve"->Set.of("points","radius");case "plane","surface"->Set.of("points");case "polygon"->Set.of("points","height");case "cylinder"->Set.of("center","radius","height","thickness");case "ellipsoid","dome"->Set.of("center","radius","thickness");default->throw bad("KIND");});keys(s,allowed.toArray(String[]::new));
        Set<Pos> out=positionSink==null?new LinkedHashSet<>():new AbstractSet<>(){long emitted;public boolean add(Pos p){positionSink.accept(p);emitted++;return true;}public int size(){return emitted==0?0:1;}public Iterator<Pos> iterator(){throw new UnsupportedOperationException();}};
        switch(kind){
            case "box","slope"->{
                Pos a=pos(s.path("min"),4096),b=pos(s.path("max"),4096);bounds(a,b);boolean slope=kind.equals("slope"),asc=bool(s,"ascending",true);String mode=choice(s,"mode","solid","solid","walls","shell"),axis=choice(s,"axis","x","x","z");int thick=integer(s,"thickness",slope?0:1,slope?0:1,4096);
                for(int x=a.x;x<=b.x;x++)for(int y=a.y;y<=b.y;y++)for(int z=a.z;z<=b.z;z++){
                    step();boolean edge=x-a.x<thick||b.x-x<thick||z-a.z<thick||b.z-z<thick;
                    if(slope){int lo=axis.equals("x")?a.x:a.z,hi=axis.equals("x")?b.x:b.z,v=axis.equals("x")?x:z;double t=hi==lo?1:(v-lo)/(double)(hi-lo);if(!asc)t=1-t;int top=a.y+(int)Math.floor((b.y-a.y)*t+1e-9);if(y<=top&&(thick==0||y>top-thick))emit(out,new Pos(x,y,z));}
                    else if(mode.equals("solid")||edge||mode.equals("shell")&&(y-a.y<thick||b.y-y<thick))emit(out,new Pos(x,y,z));
                }
            }
            case "line","curve"->{
                var ps=points(s.path("points"),kind.equals("curve")?4:2,kind.equals("curve")?4:128);double r=s.has("radius")?num(s.get("radius"),0,64):0;
                if(kind.equals("line")){for(int i=1;i<ps.size();i++)line(out,ps.get(i-1),ps.get(i),r);}
                else{double length=0;for(int i=1;i<4;i++)length+=ps.get(i).add(ps.get(i-1).mul(-1)).length();int n=Math.max(1,(int)Math.ceil(length*4));Vec last=ps.getFirst();for(int i=1;i<=n;i++){step();double t=i/(double)n,u=1-t;Vec p=ps.get(0).mul(u*u*u).add(ps.get(1).mul(3*u*u*t)).add(ps.get(2).mul(3*u*t*t)).add(ps.get(3).mul(t*t*t));line(out,last,p,r);last=p;}}
            }
            case "plane","surface"->{
                var ps=points(s.path("points"),kind.equals("plane")?3:4,kind.equals("plane")?3:4);Vec a=ps.get(0),b=ps.get(1),c=ps.get(2),d=kind.equals("plane")?b.add(c).add(a.mul(-1)):ps.get(3);
                int u=Math.max(1,(int)Math.ceil(Math.max(b.add(a.mul(-1)).max(),d.add(c.mul(-1)).max())*2)),v=Math.max(1,(int)Math.ceil(Math.max(c.add(a.mul(-1)).max(),d.add(b.mul(-1)).max())*2));
                for(int i=0;i<=u;i++)for(int j=0;j<=v;j++){step();double t=i/(double)u,w=j/(double)v;emit(out,round(a.mul((1-t)*(1-w)).add(b.mul(t*(1-w))).add(c.mul((1-t)*w)).add(d.mul(t*w))));}
            }
            case "polygon"->{
                var ps=points(s.path("points"),3,128);int height=integer(s.path("height"),1,4096);double y=ps.getFirst().y;for(var p:ps)if(Math.abs(p.y-y)>1e-8)throw bad("POLYGON_PLANE");
                double area=0;for(int i=0;i<ps.size();i++){Vec a=ps.get(i),b=ps.get((i+1)%ps.size());area+=a.x*b.z-b.x*a.z;for(int j=i+2;j<ps.size();j++){if(i==0&&j==ps.size()-1)continue;Vec c=ps.get(j),d=ps.get((j+1)%ps.size());if(segmentsCross(a,b,c,d))throw bad("POLYGON_SELF_INTERSECTION");}}if(Math.abs(area)<1e-8)throw bad("POLYGON_AREA");
                int minX=(int)Math.floor(ps.stream().mapToDouble(p->p.x).min().orElseThrow()),maxX=(int)Math.ceil(ps.stream().mapToDouble(p->p.x).max().orElseThrow()),minZ=(int)Math.floor(ps.stream().mapToDouble(p->p.z).min().orElseThrow()),maxZ=(int)Math.ceil(ps.stream().mapToDouble(p->p.z).max().orElseThrow());
                for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){step();boolean inside=false,edge=false;for(int i=0,j=ps.size()-1;i<ps.size();j=i++){step();Vec a=ps.get(i),b=ps.get(j);if(Math.abs(cross(a,b,new Vec(x,y,z)))<1e-8&&x>=Math.min(a.x,b.x)&&x<=Math.max(a.x,b.x)&&z>=Math.min(a.z,b.z)&&z<=Math.max(a.z,b.z))edge=true;if((a.z>z)!=(b.z>z)&&x<(b.x-a.x)*(z-a.z)/(b.z-a.z)+a.x)inside=!inside;}if(edge||inside)for(int dy=0;dy<height;dy++)emit(out,new Pos(x,(int)Math.round(y)+dy,z));}
            }
            case "cylinder","ellipsoid","dome"->{
                Vec c=vec(s.path("center"));boolean cyl=kind.equals("cylinder");JsonNode r=s.path("radius");if(!r.isArray()||r.size()!=(cyl?2:3))throw bad("RADIUS");double rx=num(r.get(0),.5,4096),ry=cyl?1:num(r.get(1),.5,4096),rz=num(r.get(cyl?1:2),.5,4096),th=s.has("thickness")?num(s.get("thickness"),0,4096):0;int height=cyl?integer(s.path("height"),1,4096):0;
                for(int x=(int)Math.ceil(c.x-rx);x<=Math.floor(c.x+rx);x++)for(int y=(int)Math.ceil(c.y-(cyl||kind.equals("dome")?0:ry));y<=(cyl?Math.round(c.y)+height-1:Math.floor(c.y+ry));y++)for(int z=(int)Math.ceil(c.z-rz);z<=Math.floor(c.z+rz);z++){
                    step();double dx=x-c.x,dy=y-c.y,dz=z-c.z,v=dx*dx/(rx*rx)+dz*dz/(rz*rz)+(cyl?0:dy*dy/(ry*ry));boolean inner=th>0&&rx>th&&rz>th&&(cyl||ry>th)&&dx*dx/((rx-th)*(rx-th))+dz*dz/((rz-th)*(rz-th))+(cyl?0:dy*dy/((ry-th)*(ry-th)))<1-1e-8;
                    if(v<=1+1e-8&&!inner)emit(out,new Pos(x,y,z));
                }
            }
            default->throw bad("KIND");
        }
        if(out.isEmpty())throw bad("EMPTY");return out;
    }
    private static double cross(Vec a,Vec b,Vec c){return (b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x);}
    private static boolean segmentsCross(Vec a,Vec b,Vec c,Vec d){double ab=cross(a,b,c)*cross(a,b,d),cd=cross(c,d,a)*cross(c,d,b);return ab<=0&&cd<=0&&Math.max(Math.min(a.x,b.x),Math.min(c.x,d.x))<=Math.min(Math.max(a.x,b.x),Math.max(c.x,d.x))&&Math.max(Math.min(a.z,b.z),Math.min(c.z,d.z))<=Math.min(Math.max(a.z,b.z),Math.max(c.z,d.z));}
    private static final class Material {
        String mode="solid",axis="x";int width=1,seed;List<String> states=new ArrayList<>();int[] weights;long total;
        Material(JsonNode n){if(n.isTextual())states.add(text(n));else{keys(n,"mode","states","axis","width","seed","weights");mode=choice(n,"mode","checker","checker","stripe","layers","weighted");axis=choice(n,"axis","x","x","y","z");width=integer(n,"width",1,1,4096);seed=integer(n,"seed",0,Integer.MIN_VALUE,Integer.MAX_VALUE);var a=n.path("states");if(!a.isArray()||a.isEmpty()||a.size()>32)throw bad("MATERIAL");a.forEach(v->states.add(text(v)));if(n.has("weights")&&!mode.equals("weighted"))throw bad("WEIGHTS");}
            weights=new int[states.size()];if(n.has("weights")&&(!n.get("weights").isArray()||n.get("weights").size()!=states.size()))throw bad("WEIGHTS");for(int i=0;i<weights.length;i++){weights[i]=n.has("weights")?integer(n.get("weights").get(i),1,1000000):1;total+=weights[i];}}
        String at(Pos p){int axisValue=axis.equals("x")?p.x:axis.equals("y")?p.y:p.z;int i=switch(mode){case "solid"->0;case "checker"->Math.floorMod(Math.floorDiv(p.x,width)+Math.floorDiv(p.y,width)+Math.floorDiv(p.z,width),states.size());case "stripe"->Math.floorMod(Math.floorDiv(axisValue,width),states.size());case "layers"->Math.clamp(Math.floorDiv(axisValue,width),0,states.size()-1);case "weighted"->{long h=seed^p.x*0x9e3779b97f4a7c15L^p.y*0xbf58476d1ce4e5b9L^p.z*0x94d049bb133111ebL;h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;long n=Math.floorMod(h^(h>>>31),total);int pick=0;while(n>=weights[pick])n-=weights[pick++];yield pick;}default->throw bad("MATERIAL");};return states.get(i);}
    }
    private void put(Map<Pos,Cell> out,Cell cell){step();var p=cell.pos;if(Math.abs(p.x)>4096||Math.abs(p.y)>4096||Math.abs(p.z)>4096)throw bad("LOCAL_RANGE");out.put(p,cell);if(out.size()>MAX_CELLS)throw bad("CELL_LIMIT");}
    private static Cell changed(Cell c,Pos p,String orientation){var o=new ArrayList<>(c.orientation);if(!orientation.isEmpty())o.add(orientation);return new Cell(p,c.state,List.copyOf(o));}
    private static Cell rotate(Cell c,Pos pivot,int turns){Pos p=c.pos;int x=p.x-pivot.x,z=p.z-pivot.z,t=Math.floorMod(turns,4);for(int i=0;i<t;i++){int old=x;x=-z;z=old;}return changed(c,new Pos(pivot.x+x,p.y,pivot.z+z),t==0?"":"r"+t);}
    private Map<Pos,Cell> transform(Map<Pos,Cell> cells,JsonNode t){
        String op=text(t.path("op"));var out=new LinkedHashMap<Pos,Cell>();
        switch(op){
            case "translate"->{keys(t,"op","offset");Pos offset=pos(t.path("offset"),4096);for(var c:cells.values())put(out,changed(c,c.pos.add(offset),""));}
            case "rotate","mirror"->{keys(t,op.equals("rotate")?new String[]{"op","turns","pivot"}:new String[]{"op","axis","pivot"});Pos pivot=t.has("pivot")?pos(t.get("pivot"),4096):new Pos(0,0,0);if(op.equals("rotate")){int turns=integer(t.path("turns"),-3,3);for(var c:cells.values())put(out,rotate(c,pivot,turns));}else{String axis=choice(t,"axis","x","x","z");for(var c:cells.values()){Pos p=c.pos;put(out,changed(c,new Pos(axis.equals("x")?2*pivot.x-p.x:p.x,p.y,axis.equals("z")?2*pivot.z-p.z:p.z),"m"+axis));}}}
            case "array"->{keys(t,"op","count","step");Pos n=pos(t.path("count"),2048),d=pos(t.path("step"),4096);if(n.x<1||n.y<1||n.z<1)throw bad("ARRAY");for(int x=0;x<n.x;x++)for(int y=0;y<n.y;y++)for(int z=0;z<n.z;z++)for(var c:cells.values())put(out,changed(c,c.pos.add(new Pos(x*d.x,y*d.y,z*d.z)),""));}
            case "path"->{keys(t,"op","points","spacing","orient");var ps=points(t.path("points"),2,128);double spacing=num(t.path("spacing"),.5,4096),next=0,total=0;boolean orient=bool(t,"orient",false);for(int i=1;i<ps.size();i++){Vec a=ps.get(i-1),d=ps.get(i).add(a.mul(-1));double len=d.length();if(len<1e-8)continue;for(;next<=total+len+1e-8;next+=spacing){step();Vec p=a.add(d.mul((next-total)/len));int turns=orient?(int)Math.round(Math.atan2(-d.x,d.z)/(Math.PI/2)):0;for(var c:cells.values()){Cell r=rotate(c,new Pos(0,0,0),turns);put(out,changed(r,r.pos.add(round(p)),""));}}total+=len;}if(total==0)throw bad("PATH");}
            default->throw bad("TRANSFORM");
        }
        return out;
    }
    public static Plan parse(String source){
        var cells=new LinkedHashMap<Pos,Cell>();var summary=stream(source,c->{cells.put(c.pos,c);if(cells.size()>MAX_CELLS)throw bad("CELL_LIMIT");});
        return new Plan(summary.origin,List.copyOf(cells.values()),summary.replace,summary.parts);
    }
    private static void checkRange(Pos p,int[] lo,int[] hi){int[] v={p.x,p.y,p.z};for(int i=0;i<3;i++){lo[i]=Math.min(lo[i],v[i]);hi[i]=Math.max(hi[i],v[i]);if((long)hi[i]-lo[i]+1>MAX_AXIS)throw bad("EXTENT_2048");}}
    private void transformStream(Cell cell,JsonNode transforms,int index,java.util.function.Consumer<Cell> sink){
        step();if(index==transforms.size()){sink.accept(cell);return;}JsonNode t=transforms.get(index);String op=text(t.path("op"));
        java.util.function.Consumer<Cell> next=c->transformStream(c,transforms,index+1,sink);
        if(op.equals("array")){
            keys(t,"op","count","step");Pos n=pos(t.path("count"),2048),d=pos(t.path("step"),4096);if(n.x<1||n.y<1||n.z<1)throw bad("ARRAY");
            for(int x=0;x<(d.x==0?1:n.x);x++)for(int y=0;y<(d.y==0?1:n.y);y++)for(int z=0;z<(d.z==0?1:n.z);z++)next.accept(changed(cell,cell.pos.add(new Pos(x*d.x,y*d.y,z*d.z)),""));
        }else if(op.equals("path")){
            keys(t,"op","points","spacing","orient");var ps=points(t.path("points"),2,128);double spacing=num(t.path("spacing"),.5,4096),at=0,total=0;boolean orient=bool(t,"orient",false);
            for(int i=1;i<ps.size();i++){Vec a=ps.get(i-1),d=ps.get(i).add(a.mul(-1));double len=d.length();if(len<1e-8)continue;for(;at<=total+len+1e-8;at+=spacing){step();Vec p=a.add(d.mul((at-total)/len));Cell c=rotate(cell,new Pos(0,0,0),orient?(int)Math.round(Math.atan2(-d.x,d.z)/(Math.PI/2)):0);next.accept(changed(c,c.pos.add(round(p)),""));}total+=len;}if(total==0)throw bad("PATH");
        }else for(var c:transform(Map.of(cell.pos,cell),t).values())next.accept(c);
    }
    public record StreamSummary(Pos origin,Pos min,Pos max,List<String> replace,int parts,long emissions){}
    /** Same rasterizer/material/transform rules; duplicates resolved last-wins by the consumer. */
    public static StreamSummary stream(String source,java.util.function.Consumer<Cell> sink){
        try{return new WorldGeometry().streamBuild(JSON.readTree(source),sink);}catch(RuntimeException e){throw e;}catch(Exception e){throw bad("JSON");}
    }
    private StreamSummary streamBuild(JsonNode n,java.util.function.Consumer<Cell> sink){
        keys(n,"origin","parts","replace");Pos origin=pos(n.path("origin"),30_000_000);JsonNode parts=n.path("parts");if(!parts.isArray()||parts.isEmpty()||parts.size()>64)throw bad("PARTS");var replace=new ArrayList<String>();if(n.has("replace")){var a=n.get("replace");if(!a.isArray()||a.isEmpty()||a.size()>32)throw bad("REPLACE");a.forEach(v->replace.add(text(v)));}
        int[] lo={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},hi={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};long[] emitted={0};
        for(var s:parts){Material material=new Material(s.path("material"));JsonNode ts=s.path("transforms");if(s.has("transforms")&&(!ts.isArray()||ts.size()>32))throw bad("TRANSFORMS");
            positionSink=p->transformStream(new Cell(p,material.at(p),List.of()),ts,0,c->{checkRange(c.pos,lo,hi);sink.accept(new Cell(c.pos.add(origin),c.state,c.orientation));emitted[0]++;});
            shape(s);
        }
        return new StreamSummary(origin,new Pos(lo[0],lo[1],lo[2]).add(origin),new Pos(hi[0],hi[1],hi[2]).add(origin),List.copyOf(replace),parts.size(),emitted[0]);
    }
    private WorldGeometry(){}
}
