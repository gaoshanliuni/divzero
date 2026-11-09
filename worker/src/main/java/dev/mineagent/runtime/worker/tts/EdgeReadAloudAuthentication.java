package dev.mineagent.runtime.worker.tts;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Java adaptation of rany2/edge-tts drm.py Sec-MS-GEC v2, LGPL-3.0-only.
 * Copyright (c) rany and edge-tts contributors. See bundled edge-tts license/source.
 * This class accepts only a clock instant and fixed Microsoft protocol data. It is
 * not a file/content fingerprint API. User-approved exception: read-aloud authentication only.
 */
final class EdgeReadAloudAuthentication {
    private static final int[] INITIAL={0x6A09E667,0xBB67AE85,0x3C6EF372,0xA54FF53A,0x510E527F,0x9B05688C,0x1F83D9AB,0x5BE0CD19};
    private static final int[] RESUMED={0xFD9FBEA2,0x91243CE7,0xBE499094,0xCD2E1E0A,0x6C006F49,0x04BD35AB,0xFEB0A7E4,0x03EFFBD2};
    private static final int[] SCHEDULE={0xEB24BF9E,0x1558691F,0xA0C09EE2,0x0CFA32BA,0xA622EE8C,0xD1DD9491,0xC0DD25A0,0xF9E70AB6,0xF0C31573,0x7058CE4E,0x7188E606,0x00E5AB33,0xD258B060,0x72766564};
    private static final int[] ROUND={0x428A2F98,0x71374491,0xB5C0FBCF,0xE9B5DBA5,0x3956C25B,0x59F111F1,0x923F82A4,0xAB1C5ED5,0xD807AA98,0x12835B01,0x243185BE,0x550C7DC3,0x72BE5D74,0x80DEB1FE,0x9BDC06A7,0xC19BF174,0xE49B69C1,0xEFBE4786,0x0FC19DC6,0x240CA1CC,0x2DE92C6F,0x4A7484AA,0x5CB0A9DC,0x76F988DA,0x983E5152,0xA831C66D,0xB00327C8,0xBF597FC7,0xC6E00BF3,0xD5A79147,0x06CA6351,0x14292967,0x27B70A85,0x2E1B2138,0x4D2C6DFC,0x53380D13,0x650A7354,0x766A0ABB,0x81C2C92E,0x92722C85,0xA2BFE8A1,0xA81A664B,0xC24B8B70,0xC76C51A3,0xD192E819,0xD6990624,0xF40E3585,0x106AA070,0x19A4C116,0x1E376C08,0x2748774C,0x34B0BCB5,0x391C0CB3,0x4ED8AA4A,0x5B9CCA4F,0x682E6FF3,0x748F82EE,0x78A5636F,0x84C87814,0x8CC70208,0x90BEFFFA,0xA4506CEB,0xBEF9A3F7,0xC67178F2};
    private static final int[][] COEFFICIENTS={{0xE82E91B1,0xB32700D0},{0x44DE24C6,0x88DFF51D},{0x3BEB65BC,0x29514038},{0xC89C5933,0xB328CBDF},{0x8D1EC211,0xD405B4D5},{0x314EF5D5,0x26E55F3C},{0xE92491B1,0x607801C6},{0xF0A5B9D5,0x82B2C1FB},{0x06064CF3,0xFA51A3DA},{0xDA79FA60,0x207CC464},{0xBED20560,0x3C3C74F3},{0x89F9C9F8,0xD2D14B61},{0x35FA38C6,0xBB04A2C1},{0xCB49E3C6,0xC5626167}};

    private static int sigma0(int v){return Integer.rotateRight(v,7)^Integer.rotateRight(v,18)^(v>>>3);}
    private static int sigma1(int v){return Integer.rotateRight(v,17)^Integer.rotateRight(v,19)^(v>>>10);}
    private static void expand(int[] words,int first){for(int i=first;i<64;i++)words[i]=sigma1(words[i-2])+words[i-7]+sigma0(words[i-15])+words[i-16];}
    private static int[] rounds(int[] initial,int[] words,int first){
        int a=initial[0],b=initial[1],c=initial[2],d=initial[3],e=initial[4],f=initial[5],g=initial[6],h=initial[7];
        for(int i=first;i<64;i++){
            int t1=h+(Integer.rotateRight(e,6)^Integer.rotateRight(e,11)^Integer.rotateRight(e,25))+(g^(e&(f^g)))+(first==14&&i==14?0xC9DC06A7:ROUND[i])+words[i];
            int t2=(Integer.rotateRight(a,2)^Integer.rotateRight(a,13)^Integer.rotateRight(a,22))+((a&b)|(c&(a|b)));
            h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
        }return new int[]{a,b,c,d,e,f,g,h};
    }
    private static int[] initial(byte[] data){
        int[] words=new int[64];words[14]=((data[0]&255)<<16)|((data[1]&255)<<8)|(data[2]&255);words[15]=ByteBuffer.wrap(data,3,4).getInt();
        for(int i=16;i<30;i++){
            int value=SCHEDULE[i-16];if(i-2>=14)value+=sigma1(words[i-2])^(i==16?0x000B92C0:0);
            if(i-7>=14)value+=words[i-7];if(i-15>=14)value+=sigma0(words[i-15])^(i==29?0x059C0B80:0);words[i]=value;
        }
        words[30]=sigma1(words[28])+words[23]+sigma0(words[15])+words[14]+0xC9DC06A7-ROUND[14];expand(words,31);
        int[] result=rounds(RESUMED,words,14);for(int i=0;i<8;i++)result[i]+=INITIAL[i];return result;
    }
    private static byte[] protocolDigest(byte[] data){
        int length=57+data.length;var padded=ByteBuffer.allocate(data.length-7+1+Math.floorMod(55-length,64)+8).put(data,7,data.length-7).put((byte)128);
        while(padded.remaining()>8)padded.put((byte)0);padded.putLong(length*8L);padded.flip();int[] state=initial(data);
        while(padded.hasRemaining()){int[] words=new int[64];for(int i=0;i<16;i++)words[i]=padded.getInt();expand(words,16);int[] next=rounds(state,words,0);for(int i=0;i<8;i++)state[i]+=next[i];}
        var out=ByteBuffer.allocate(32);for(int word:state)out.putInt(word);return out.array();
    }
    private static byte[] cs64(byte[] data,byte[] digest){
        var key=ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN);int a=key.getInt()|1,b=key.getInt()|1;int[] factors={a*0x82893933,b*0x82893933};
        int count=((data.length+1+7)/8)*2;var padded=ByteBuffer.allocate(count*4).order(ByteOrder.LITTLE_ENDIAN).put((byte)0).put(data);padded.rewind();
        int[] terms=new int[14+count],offsets=new int[14+count];for(int i=0;i<14;i++){terms[i]=COEFFICIENTS[i][0]*factors[i%2];offsets[i]=COEFFICIENTS[i][1];}
        for(int i=0;i<count;i++){int word=padded.getInt();terms[14+i]=word*(i%2==0?a:b);offsets[14+i]=Integer.rotateLeft(word,16)*(i%2==0?0xEF0569FB:0xC31713DB);if(i==0){terms[14+i]+=0xF2F90B1A*factors[0];offsets[14+i]+=0x0B1A0000;}}
        int swapAcc=0,swapLast=0,state=0,acc=0;
        for(int i=0;i<terms.length;i+=2){
            int x=terms[i],y=terms[i+1],t=Integer.rotateLeft(offsets[i]+x,16),q=t*0x76F50F34;swapAcc+=Integer.rotateLeft(q,16)*0xC3EFEA97+q;
            t=Integer.rotateLeft(offsets[i+1]+y,16);q=t*0x1881CE3C;swapLast=Integer.rotateLeft(q,16)*0x35BD1EC9+q;swapAcc+=swapLast;
            state=state*a+x;state=Integer.rotateLeft(state,16)*0xCF98B111;state=Integer.rotateLeft(state,16)*0x87085B9F;state=Integer.rotateLeft(state,16)*0x12CEB96D;state=Integer.rotateLeft(state,16)*0x257E1D83;acc+=state;
            t=state*b+y;t=Integer.rotateLeft(t,16)*0xA27416F5;t=Integer.rotateLeft(t,16)*0xD38396FF;state=Integer.rotateLeft(Integer.rotateLeft(t,16)*0x7C932B89,16)*0xBFA49F69;acc+=state;
        }return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(swapLast^state).putInt(swapAcc^acc).array();
    }
    static String token(Instant instant){long seconds=Math.addExact(instant.getEpochSecond(),11644473600L);long ticks=Math.multiplyExact(seconds-Math.floorMod(seconds,300),10000000L);byte[] data=(Long.toString(ticks)+EdgeTtsProtocol.TRUSTED_CLIENT_TOKEN).getBytes(StandardCharsets.US_ASCII);byte[] digest=protocolDigest(data);return HexFormat.of().withUpperCase().formatHex(cs64(data,digest))+"-"+HexFormat.of().withUpperCase().formatHex(digest);}
    private EdgeReadAloudAuthentication(){}
}
