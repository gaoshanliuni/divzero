package dev.mineagent.runtime.core.building;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Java-edition NBT as data only. Strict structure, duplicate-key and allocation bounds. */
public final class BuildingNbt {
    public static final long MAX_BYTES=256L*1024*1024;
    private long left=MAX_BYTES,nodes;
    private void charge(long n)throws IOException{if(n<0||(left-=n)<0)throw new IOException("BUILDING_NBT_SIZE");}
    public static Map<String,Object> read(Path file)throws IOException{try(var in=new BufferedInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS))){in.mark(2);int a=in.read(),b=in.read();in.reset();return read(a==31&&b==139?new GZIPInputStream(in):in);}}
    public static Map<String,Object> read(InputStream input)throws IOException{
        var parser=new BuildingNbt();var bounded=new FilterInputStream(input){long remaining=MAX_BYTES;@Override public int read()throws IOException{if(remaining<=0)throw new IOException("BUILDING_NBT_SIZE");int v=super.read();if(v>=0)remaining--;return v;}@Override public int read(byte[] b,int o,int n)throws IOException{if(remaining<=0)throw new IOException("BUILDING_NBT_SIZE");int v=in.read(b,o,(int)Math.min(n,remaining));if(v>0)remaining-=v;return v;}};
        var d=new DataInputStream(bounded);if(d.readUnsignedByte()!=10)throw new IOException("BUILDING_NBT_ROOT");d.readUTF();var result=compound(parser.tag(d,10,0));if(d.read()!=-1)throw new IOException("BUILDING_NBT_TRAILING");return result;
    }
    private Object tag(DataInputStream d,int t,int depth)throws IOException{
        if(depth>64)throw new IOException("BUILDING_NBT_DEPTH");if(++nodes>4_000_000)throw new IOException("BUILDING_NBT_NODE_LIMIT");charge(24);
        return switch(t){case 1->d.readByte();case 2->d.readShort();case 3->d.readInt();case 4->d.readLong();case 5->d.readFloat();case 6->d.readDouble();
            case 7->{int n=d.readInt();charge(n);byte[] a=new byte[n];d.readFully(a);yield a;}
            case 8->{String s=d.readUTF();charge(s.length()*2L);yield s;}
            case 9->{int type=d.readUnsignedByte(),n=d.readInt();charge(n*8L);if(n<0||n>4_000_000||type>12||type==0&&n>0)throw new IOException("BUILDING_NBT_LIST");var a=new ArrayList<Object>(n);for(int i=0;i<n;i++)a.add(tag(d,type,depth+1));yield a;}
            case 10->{var a=new LinkedHashMap<String,Object>();for(int type;(type=d.readUnsignedByte())!=0;){String name=d.readUTF();charge(name.length()*2L+32);if(a.containsKey(name))throw new IOException("BUILDING_NBT_DUPLICATE");a.put(name,tag(d,type,depth+1));}yield a;}
            case 11->{int n=d.readInt();charge(n*4L);if(n<0)throw new IOException("BUILDING_NBT_ARRAY");int[] a=new int[n];for(int i=0;i<n;i++)a[i]=d.readInt();yield a;}
            case 12->{int n=d.readInt();charge(n*8L);if(n<0)throw new IOException("BUILDING_NBT_ARRAY");long[] a=new long[n];for(int i=0;i<n;i++)a[i]=d.readLong();yield a;}
            default->throw new IOException("BUILDING_NBT_TAG");};
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> compound(Object n){if(!(n instanceof Map<?,?>))throw new IllegalArgumentException("BUILDING_NBT_COMPOUND");return (Map<String,Object>)n;}
    @SuppressWarnings("unchecked") public static List<Object> list(Object n){if(!(n instanceof List<?>))throw new IllegalArgumentException("BUILDING_NBT_LIST");return (List<Object>)n;}
    public static int integer(Object n){if(!(n instanceof Byte||n instanceof Short||n instanceof Integer))throw new IllegalArgumentException("BUILDING_NBT_INTEGER");return ((Number)n).intValue();}
    public static String string(Object n){if(!(n instanceof String s)||s.length()>65535)throw new IllegalArgumentException("BUILDING_NBT_STRING");return s;}
    public static Map<String,Object> child(Map<String,Object> n,String k){return compound(n.getOrDefault(k,Map.of()));}
    public static List<Object> items(Map<String,Object> n,String k){return list(n.getOrDefault(k,List.of()));}
    public static int number(Map<String,Object> n,String k,int fallback){return n.containsKey(k)?integer(n.get(k)):fallback;}
    public static String state(Map<String,Object> n){String name=string(n.get("Name"));if(!name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("BUILDING_BLOCK_NAME");var props=child(n,"Properties");var parts=new ArrayList<String>();for(String key:new TreeSet<>(props.keySet())){String value=string(props.get(key));if(!key.matches("[a-z0-9_]{1,64}")||!value.matches("[a-zA-Z0-9_.-]{1,64}"))throw new IllegalArgumentException("BUILDING_BLOCK_PROPERTY");parts.add(key+"="+value);}return name+(parts.isEmpty()?"":"["+String.join(",",parts)+"]");}
    public static byte[] encode(Map<String,Object> tag)throws IOException{var out=new ByteArrayOutputStream();try(var data=new DataOutputStream(out)){data.writeByte(10);data.writeUTF("");write(data,tag,0);}if(out.size()>16*1024*1024)throw new IOException("BUILDING_NBT_PAYLOAD_SIZE");return out.toByteArray();}
    private static int type(Object o){return o instanceof Byte?1:o instanceof Short?2:o instanceof Integer?3:o instanceof Long?4:o instanceof Float?5:o instanceof Double?6:o instanceof byte[]?7:o instanceof String?8:o instanceof List?9:o instanceof Map?10:o instanceof int[]?11:o instanceof long[]?12:0;}
    private static void write(DataOutputStream d,Object o,int depth)throws IOException{if(depth>64)throw new IOException("BUILDING_NBT_DEPTH");switch(type(o)){case 1->d.writeByte((byte)o);case 2->d.writeShort((short)o);case 3->d.writeInt((int)o);case 4->d.writeLong((long)o);case 5->d.writeFloat((float)o);case 6->d.writeDouble((double)o);case 7->{var a=(byte[])o;d.writeInt(a.length);d.write(a);}case 8->d.writeUTF((String)o);case 9->{var a=(List<?>)o;int t=a.isEmpty()?0:type(a.getFirst());d.writeByte(t);d.writeInt(a.size());for(var v:a){if(type(v)!=t)throw new IOException("BUILDING_NBT_LIST_TYPES");write(d,v,depth+1);}}case 10->{for(var e:compound(o).entrySet()){d.writeByte(type(e.getValue()));d.writeUTF(e.getKey());write(d,e.getValue(),depth+1);}d.writeByte(0);}case 11->{int[] a=(int[])o;d.writeInt(a.length);for(int v:a)d.writeInt(v);}case 12->{long[] a=(long[])o;d.writeInt(a.length);for(long v:a)d.writeLong(v);}default->throw new IOException("BUILDING_NBT_TAG");}}
    private BuildingNbt(){}
}
