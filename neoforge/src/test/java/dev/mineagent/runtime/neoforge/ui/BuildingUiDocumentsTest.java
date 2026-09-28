package dev.mineagent.runtime.neoforge.ui;

import dev.mineagent.runtime.api.ui.UiProtocol.*;
import dev.mineagent.runtime.core.packages.RuntimePackageCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildingUiDocumentsTest {
    private static Session session(){var owner=UUID.randomUUID();return new Session(UUID.randomUUID(),UUID.randomUUID(),new Binding("runtime-shell",UUID.randomUUID(),1,"1","trusted/shell",UUID.randomUUID(),owner,owner,ActorKind.PLAYER,null,0,"",false,Set.of("task.manage")),1,1,Long.MAX_VALUE,Status.RENDERED);}
    private static final String SOURCE="""
        {"id":"house","name":"住宅🏡","dimension":"minecraft:overworld","origin":[0,70,0],"components":[{"id":"floor","parts":[{"kind":"box","min":[0,0,0],"max":[3,0,3],"material":"minecraft:stone"}]}],"checks":[{"id":"door","component":"floor","kind":"clearance","min":[1,1,0],"max":[1,2,0]}]}
        """;
    @Test void largePlanRoundTripsOnlyAfterAllScopedChunksMatchTheHash()throws Exception{
        var transfers=new BuildingUiDocuments();var session=session();var agent=UUID.randomUUID();String source=SOURCE+" ".repeat(40000);byte[] bytes=source.getBytes(StandardCharsets.UTF_8);
        var start=transfers.begin(session,agent,Map.of("id","house","revision","4","sha256",RuntimePackageCanonicalizer.sha256(bytes),"size",Integer.toString(bytes.length)));String upload=start.get("uploadId");
        assertThrows(IllegalStateException.class,()->transfers.finish(session,agent,Map.of("uploadId",upload)));
        for(int at=0;at<bytes.length;at+=8192){int next=Math.min(at+8192,bytes.length);var receipt=transfers.append(session,agent,Map.of("uploadId",upload,"offset",Integer.toString(at),"bytes",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,at,next))));assertEquals(Integer.toString(next),receipt.get("offset"));}
        var plan=transfers.finish(session,agent,Map.of("uploadId",upload));assertEquals(source,plan.source());assertEquals(4,plan.revision());assertEquals("house",plan.building());assertThrows(SecurityException.class,()->transfers.finish(session,agent,Map.of("uploadId",upload)));
    }
    @Test void uploadsRejectAnotherSessionAgentExpiredLeaseAndWrongByteHash()throws Exception{
        var now=new AtomicLong();var transfers=new BuildingUiDocuments(now::get);var session=session();var agent=UUID.randomUUID();byte[] bytes=SOURCE.getBytes(StandardCharsets.UTF_8);var start=transfers.begin(session,agent,Map.of("id","house","revision","0","sha256","0".repeat(64),"size",Integer.toString(bytes.length)));String upload=start.get("uploadId");var chunk=Map.of("uploadId",upload,"offset","0","bytes",Base64.getEncoder().encodeToString(bytes));
        assertThrows(SecurityException.class,()->transfers.append(session(),agent,chunk));assertThrows(SecurityException.class,()->transfers.append(session,UUID.randomUUID(),chunk));transfers.append(session,agent,chunk);assertThrows(IllegalArgumentException.class,()->transfers.finish(session,agent,Map.of("uploadId",upload)));now.set(120000);assertThrows(SecurityException.class,()->transfers.finish(session,agent,Map.of("uploadId",upload)));
    }
    @Test void documentChunksPreserveUnicodeAndRejectChangedSnapshots()throws Exception{
        String source="报告🏡".repeat(9000),hash="";int offset=0;var out=new StringBuilder();do{var part=BuildingUiDocuments.part(source,offset,hash);hash=part.get("sha256").toString();out.append(part.get("text"));offset=((Number)part.get("nextOffset")).intValue();if(!(Boolean)part.get("more"))break;}while(true);assertEquals(source,out.toString());String expected=hash;assertThrows(IllegalStateException.class,()->BuildingUiDocuments.part(source+"changed",0,expected));
    }
}
