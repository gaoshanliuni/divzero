package dev.mineagent.runtime.core.conversation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CapabilitySessionTest {
    @Test void vanillaProductionAndPeerMessagesDoNotPreloadAuthoringOrChatSettings(){
        var scope=new CapabilitySession();scope.preload("请协作，先给伙伴发消息确认材料，用工作台合成现有钻石盔甲，熔炉烧制玻璃，再建房并把盔甲存入箱子。",List.of());
        assertTrue(scope.groups().containsAll(List.of("cooperation","production","building")));
        assertFalse(scope.groups().contains("content"));assertFalse(scope.groups().contains("chat"));assertFalse(scope.groups().contains("world"));
        assertTrue(scope.tools().containsAll(List.of("inspect_recipes","process_items","send_peer_message","stop_actions")));
        var custom=new CapabilitySession();custom.preload("创建一种自定义新物品召唤权杖，并注册工作台合成配方",List.of());
        assertTrue(custom.groups().contains("content"));assertTrue(custom.groups().contains("production"));
        assertEquals("LOADED",scope.load("world").get("status"));
    }
    @Test void packageActivationIsOnDemandAndDimensionBound(){
        var scope=new CapabilitySession();assertFalse(scope.tools().contains("activate_package_version"));
        scope.load("content");assertTrue(scope.tools().contains("activate_package_version"));
        assertFalse(ToolExecutionTraits.of("activate_package_version").dimensionIndependent());
        assertFalse(ToolExecutionTraits.of("activate_package_version").readOnly());
    }
    @Test void ordinaryChatHasOnlyResidentsAndNeverAllSchemas(){
        var scope=new CapabilitySession();scope.preload("你好，今天心情怎么样？",List.of("building"));
        assertEquals(CapabilityCatalog.RESIDENT,scope.tools());assertTrue(scope.definitions().size()<ConversationTools.ALL.size()/4);
        assertFalse(scope.definitions().stream().anyMatch(t->t.parameters().contains("rects")));
    }
    @Test void explicitIntentCombinesGroupsAndReusesWithoutReordering(){
        var scope=new CapabilitySession();scope.preload("帮我建造一栋房子，再创建一个 HUD 界面。",List.of());
        assertTrue(scope.groups().containsAll(List.of("building","ui")));var previous=scope.tools();
        assertEquals("ALREADY_LOADED",scope.load("building").get("status"));assertEquals(previous,scope.tools());
        scope.load("appearance");assertEquals(previous,scope.tools().subList(0,previous.size()));assertTrue(scope.tools().contains("stop_actions"));
        assertFalse(scope.tools().contains("inspect_webui"));assertFalse(scope.tools().contains("inspect_skills"));
    }
    @Test void everyBackendCapabilityRemainsDiscoverableIncludingCompatibilityAliases(){
        var covered=new HashSet<>(CapabilityCatalog.RESIDENT);for(var group:CapabilityCatalog.GROUPS)covered.addAll(group.tools());
        covered.addAll(CapabilityCatalog.ALIASES.keySet());assertEquals(ConversationTools.NAMES,covered);
        assertTrue(ConversationTools.NAMES.contains("start_skill"));assertThrows(IllegalArgumentException.class,()->new CapabilitySession().load("missing"));
    }
    @Test void onlyClearContinuationRestoresTaskGroups(){
        var scope=new CapabilitySession();scope.preload("继续刚才的任务",List.of("building","files"));assertEquals(List.of("building","files"),scope.groups());
        var separate=new CapabilitySession();assertEquals(CapabilityCatalog.RESIDENT,separate.tools());
    }
}
