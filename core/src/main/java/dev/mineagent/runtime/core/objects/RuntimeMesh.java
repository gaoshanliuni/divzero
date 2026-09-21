package dev.mineagent.runtime.core.objects;
import com.fasterxml.jackson.databind.*;
import java.util.*;
/** Versioned geometry resource, not a gameplay DSL. Raw triangles and compound cuboids share one renderer. */
public record RuntimeMesh(List<Vertex> vertices,List<Triangle> triangles,Collision collision,Physics physics,String texture) {
    public static final int MAX_BYTES=262144,MAX_VERTICES=4096,MAX_TRIANGLES=8192;
    public RuntimeMesh{vertices=List.copyOf(vertices);triangles=List.copyOf(triangles);Objects.requireNonNull(collision);Objects.requireNonNull(physics);Objects.requireNonNull(texture);}
    public record Vertex(float x,float y,float z,float u,float v,float nx,float ny,float nz){public Vertex(float x,float y,float z,float u,float v){this(x,y,z,u,v,0,0,0);}public boolean hasNormal(){return nx!=0||ny!=0||nz!=0;}}
    public record Triangle(int a,int b,int c,int color){}
    public record Collision(double width,double height,double depth,boolean nonSolid){public Collision(double width,double height,double depth){this(width,height,depth,false);}public Collision{for(double v:new double[]{width,height,depth})if(!Double.isFinite(v)||v<0.05||v>32)throw new IllegalArgumentException("OBJECT_COLLISION_BOUNDS");}}
    public record Physics(boolean dynamic,double mass,double gravity,double restitution,double drag){public Physics{if(!Double.isFinite(mass)||mass<=0||mass>10000||!Double.isFinite(gravity)||gravity<0||gravity>1||!Double.isFinite(restitution)||restitution<0||restitution>1||!Double.isFinite(drag)||drag<0||drag>1)throw new IllegalArgumentException("OBJECT_PHYSICS_BOUNDS");}}
    private static final Map<String,RuntimeMesh> CACHE=new LinkedHashMap<>(32,.75f,true);private static long cacheBytes;
    private static long memory(String source,RuntimeMesh mesh){return 2L*source.length()+48L*mesh.vertices().size()+32L*mesh.triangles().size();}
    public static RuntimeMesh parse(String source){
        if(source==null||source.length()>MAX_BYTES)throw new IllegalArgumentException("RUNTIME_MESH_INVALID");
        synchronized(CACHE){var old=CACHE.get(source);if(old!=null)return old;}
        var mesh=parseUncached(source);synchronized(CACHE){var old=CACHE.get(source);if(old!=null)return old;long bytes=memory(source,mesh);while(!CACHE.isEmpty()&&(CACHE.size()>=32||cacheBytes+bytes>16L*1024*1024)){var entry=CACHE.entrySet().iterator().next();cacheBytes-=memory(entry.getKey(),entry.getValue());CACHE.remove(entry.getKey());}CACHE.put(source,mesh);cacheBytes+=bytes;}return mesh;
    }
    private static RuntimeMesh parseUncached(String source){
        try{
            if(source==null||source.length()>MAX_BYTES)throw new IllegalArgumentException();
            var root=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(source);
            int version=root.path("version").intValue();if(!root.path("version").isIntegralNumber()||!root.path("version").canConvertToInt()||!Set.of(1,2).contains(version))throw new IllegalArgumentException();
            keys(root,version==1?Set.of("version","vertices","triangles","boxes","collision","physics","texture"):Set.of("version","vertices","triangles","boxes","primitives","collision","collisionMode","physics","texture"));
            var vertices=new ArrayList<Vertex>();var triangles=new ArrayList<Triangle>();
            if(root.has("vertices")){array(root.path("vertices"),MAX_VERTICES);for(var v:root.path("vertices")){arraySize(v,version==2&&v.size()==8?8:5);float nx=0,ny=0,nz=0;if(v.size()==8){double x=number(v.get(5),-1,1),y=number(v.get(6),-1,1),z=number(v.get(7),-1,1),n=Math.sqrt(x*x+y*y+z*z);if(n<.000001)throw new IllegalArgumentException();nx=(float)(x/n);ny=(float)(y/n);nz=(float)(z/n);}vertices.add(new Vertex((float)number(v.get(0),-32,32),(float)number(v.get(1),-32,32),(float)number(v.get(2),-32,32),(float)number(v.get(3),-64,64),(float)number(v.get(4),-64,64),nx,ny,nz));}}
            if(root.has("triangles")){array(root.path("triangles"),MAX_TRIANGLES);for(var t:root.path("triangles")){arraySize(t,4);int[] n=new int[3];for(int i=0;i<3;i++){if(!t.get(i).isIntegralNumber()||!t.get(i).canConvertToInt())throw new IllegalArgumentException();n[i]=t.get(i).intValue();if(n[i]<0||n[i]>=vertices.size())throw new IllegalArgumentException();}triangles.add(new Triangle(n[0],n[1],n[2],color(t.get(3))));}}
            if(root.has("boxes")){array(root.path("boxes"),128);for(var box:root.path("boxes")){keys(box,Set.of("from","to","color"));arraySize(box.path("from"),3);arraySize(box.path("to"),3);float[] a=new float[3],b=new float[3];for(int i=0;i<3;i++){a[i]=(float)number(box.path("from").get(i),-32,32);b[i]=(float)number(box.path("to").get(i),-32,32);if(b[i]-a[i]<0.001)throw new IllegalArgumentException();}box(vertices,triangles,a,b,color(box.path("color")));}}
            if(root.has("primitives")){array(root.path("primitives"),64);for(var primitive:root.path("primitives"))ParametricMeshes.append(primitive,vertices,triangles);}
            if(vertices.isEmpty()||triangles.isEmpty()||vertices.size()>MAX_VERTICES||triangles.size()>MAX_TRIANGLES)throw new IllegalArgumentException();
            for(var t:triangles){var a=vertices.get(t.a());var b=vertices.get(t.b());var c=vertices.get(t.c());double x=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y),y=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z),z=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);double area=x*x+y*y+z*z;double ab=(b.x-a.x)*(b.x-a.x)+(b.y-a.y)*(b.y-a.y)+(b.z-a.z)*(b.z-a.z),ac=(c.x-a.x)*(c.x-a.x)+(c.y-a.y)*(c.y-a.y)+(c.z-a.z)*(c.z-a.z);if(version==1?area<1e-12:area<=1e-30||area<ab*ac*1e-12)throw new IllegalArgumentException();}
            var c=root.path("collision");arraySize(c,6);double[] b=new double[6];for(int i=0;i<6;i++)b[i]=number(c.get(i),-16,32);
            if(Math.abs(b[0]+b[3])>1e-6||Math.abs(b[2]+b[5])>1e-6||b[1]!=0)throw new IllegalArgumentException();String mode=root.path("collisionMode").asText("solid");if(root.has("collisionMode")&&(!root.path("collisionMode").isTextual()||!Set.of("solid","none").contains(mode)))throw new IllegalArgumentException();var collision=new Collision(b[3]-b[0],b[4],b[5]-b[2],mode.equals("none"));
            var p=root.path("physics");Physics physics=new Physics(false,1,0,0,1);
            if(root.has("physics")){keys(p,Set.of("dynamic","mass","gravity","restitution","drag"));if(!p.path("dynamic").isBoolean())throw new IllegalArgumentException();physics=new Physics(p.path("dynamic").booleanValue(),number(p.path("mass"),0.0001,10000),number(p.path("gravity"),0,1),number(p.path("restitution"),0,1),number(p.path("drag"),0,1));}
            if(collision.nonSolid()&&physics.dynamic())throw new IllegalArgumentException("NON_SOLID_DYNAMIC_UNSUPPORTED");
            String texture="";if(root.has("texture")){if(!root.path("texture").isTextual())throw new IllegalArgumentException();texture=root.path("texture").textValue();dev.mineagent.runtime.api.packages.RuntimeEntrypoint.requireRelativePath(texture);if(!texture.endsWith(".png")||texture.length()>256)throw new IllegalArgumentException();}
            return new RuntimeMesh(vertices,triangles,collision,physics,texture);
        }catch(Exception e){throw new IllegalArgumentException("RUNTIME_MESH_INVALID",e);}
    }
    static void keys(JsonNode node,Set<String> allowed){if(!node.isObject())throw new IllegalArgumentException();node.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException();});}
    static void array(JsonNode node,int max){if(!node.isArray()||node.size()>max)throw new IllegalArgumentException();}
    static void arraySize(JsonNode node,int size){array(node,size);if(node.size()!=size)throw new IllegalArgumentException();}
    static double number(JsonNode node,double min,double max){if(node==null||!node.isNumber()||!Double.isFinite(node.doubleValue())||node.doubleValue()<min||node.doubleValue()>max)throw new IllegalArgumentException();return node.doubleValue();}
    static int color(JsonNode node){if(!node.isTextual()||!node.textValue().matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))throw new IllegalArgumentException();String s=node.textValue().substring(1);return (int)Long.parseLong(s.length()==6?"ff"+s:s,16);}
    private static void quad(List<Vertex> v,List<Triangle> t,int color,float... xyz){int n=v.size();float[][] uv={{0,1},{1,1},{1,0},{0,0}};for(int i=0;i<4;i++)v.add(new Vertex(xyz[i*3],xyz[i*3+1],xyz[i*3+2],uv[i][0],uv[i][1]));t.add(new Triangle(n,n+1,n+2,color));t.add(new Triangle(n,n+2,n+3,color));}
    private static void box(List<Vertex> v,List<Triangle> t,float[] a,float[] b,int c){float x=a[0],y=a[1],z=a[2],X=b[0],Y=b[1],Z=b[2];
        ParametricMeshes.budget(v,t,24,12);
        quad(v,t,c,x,y,z,x,y,Z,x,Y,Z,x,Y,z);quad(v,t,c,X,y,Z,X,y,z,X,Y,z,X,Y,Z);
        quad(v,t,c,x,y,Z,x,y,z,X,y,z,X,y,Z);quad(v,t,c,x,Y,z,x,Y,Z,X,Y,Z,X,Y,z);
        quad(v,t,c,X,y,z,x,y,z,x,Y,z,X,Y,z);quad(v,t,c,x,y,Z,X,y,Z,X,Y,Z,x,Y,Z);
    }
}
