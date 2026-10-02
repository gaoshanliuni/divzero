package dev.mineagent.runtime.core.conversation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameContentRoutingTest {
    @Test void ordinarySummoningStaffLoadsNativeAuthoringAndRejectsMistakenPython(){
        for(String request:List.of("做一个召唤权杖","给我制作一把新法杖，右键召唤生物","Create a summoning wand","创建一个新物品，不需要Python")){
            var session=new CapabilitySession();session.preload(request,List.of());
            assertTrue(session.groups().containsAll(List.of("items","content")),request);assertTrue(session.tools().contains("generate_content_package"));assertFalse(session.tools().contains("python_execute"));
            assertEquals("GAME_CONTENT_TOOL_REQUIRED",session.load("host").get("error"));session.used("python_execute");assertFalse(session.tools().contains("python_execute"));
            assertEquals("NOT_STARTED",session.admission("python_execute").orElseThrow().get("executionState"));assertTrue(session.admission("generate_content_package").isEmpty());
        }
    }
    @Test void explicitComputerTasksRemainAvailableAndNegativeMentionsDoNotLoadHost(){
        for(String request:List.of("请用Python生成法杖的模型数据","帮我在电脑上打开记事本","Use Python to generate an item model")){
            var session=new CapabilitySession();session.preload(request,List.of());assertTrue(session.groups().contains("host"),request);assertTrue(session.admission("python_execute").isEmpty());
        }
        assertFalse(GameContentRouting.hostRequested("做一个召唤权杖，不用Python"));
        assertFalse(GameContentRouting.hostRequested("Create a wand without Python"));
        var ordinary=new CapabilitySession();ordinary.preload("你好",List.of());assertEquals(CapabilityCatalog.RESIDENT,ordinary.tools());
    }
    @Test void gameAuthoringDoesNotInheritAnUnrelatedHostGroup(){
        var session=new CapabilitySession();session.preload("继续之前的制作召唤权杖，不要Python",List.of("host","content"));assertFalse(session.groups().contains("host"));assertTrue(session.groups().contains("content"));
    }
}
