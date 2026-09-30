package dev.mineagent.runtime.worker;
import dev.mineagent.runtime.worker.provider.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class PlanningDisclosureTest {
    @Test void legacyPlannerLoadsOnlyRequestedSchemasAndReusesWithinTask(){
        var state=new PlanningDisclosure.Session();var catalog=List.of(new ToolDefinition("move_to","MOVE_RULES","{}"),new ToolDefinition("create_ui_package","UI_RULES","{}"),new ToolDefinition("refresh_native_api","NATIVE_RULES","{}"),new ToolDefinition("finish_task","VERIFY","{}"));var calls=new AtomicInteger();
        var result=PlanningDisclosure.complete(state,catalog,"GENERAL","你好",(tools,messages)->{
            int call=calls.getAndIncrement();assertEquals("system",messages.getFirst().get("role"));assertTrue(tools.stream().anyMatch(t->t.name().equals("stop_actions")));
            if(call==0){assertEquals(4,tools.size());assertFalse(messages.toString().contains("method/descriptor"));return new ToolCompletion("",List.of(new ToolCall("load1","skill","{\"name\":\"native\"}")),"fixture","fixture","required_reasoning");}
            assertTrue(tools.stream().anyMatch(t->t.name().equals("refresh_native_api")));assertFalse(tools.stream().anyMatch(t->t.name().equals("move_to")||t.name().equals("create_ui_package")));
            assertTrue(messages.toString().contains("required_reasoning"));assertTrue(messages.toString().contains("<skill_content"));return new ToolCompletion("",List.of(new ToolCall("read1","refresh_native_api","{}")));
        });assertEquals(2,calls.get());assertEquals("refresh_native_api",result.toolCalls().getFirst().name());
        PlanningDisclosure.complete(state,catalog,"GENERAL","接着处理",(tools,messages)->{assertTrue(tools.stream().anyMatch(t->t.name().equals("refresh_native_api")));assertFalse(tools.stream().anyMatch(t->t.name().equals("create_ui_package")));return new ToolCompletion("继续",List.of());});
    }
}
