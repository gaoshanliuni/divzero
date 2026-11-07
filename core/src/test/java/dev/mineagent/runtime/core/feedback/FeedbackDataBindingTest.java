package dev.mineagent.runtime.core.feedback;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FeedbackDataBindingTest {
    @Test void planRoundTripPreservesAbsentConditionWithoutTurningItIntoAnExplicitValue()throws Exception{
        var b=new FeedbackDataBinding(UUID.randomUUID(),"state",1,Set.of("entry"),Set.of("entry"));
        var p=FeedbackTransactionPlan.parse(b,"{\"transaction\":{\"schema_version\":1,\"conditions\":[{\"key\":\"entry\",\"test\":\"ABSENT\"}],\"writes\":[{\"key\":\"entry\",\"op\":\"PUT\",\"value\":\"new\"}]}}");
        var json=new com.fasterxml.jackson.databind.ObjectMapper();assertEquals(p,json.readValue(json.writeValueAsBytes(p),FeedbackTransactionPlan.class));
    }
    @Test void planVersionIsOpaqueButCanonicalTransactionAndMutableValuesRemainChecked(){
        var b=new FeedbackDataBinding(UUID.randomUUID(),"state",1,Set.of("entry"),Set.of("entry"));
        var p=FeedbackTransactionPlan.parse(b,"{\"transaction\":{\"schema_version\":1,\"conditions\":[],\"writes\":[{\"key\":\"entry\",\"op\":\"PUT\",\"value\":{\"text\":\"original\"}}]}}");
        assertThrows(IllegalArgumentException.class,()->new FeedbackTransactionPlan(p.transaction(),p.canonical(),"invalid-reference"));
        var other=FeedbackTransactionPlan.parse(b,"{\"transaction\":"+p.canonical()+"}");
        assertEquals(p.canonical(),other.canonical());assertNotEquals(p.sha256(),other.sha256());
        assertThrows(IllegalArgumentException.class,()->new FeedbackTransactionPlan(p.transaction(),p.canonical().replace("original","changed"),p.sha256()));
        var input=p.transaction();((com.fasterxml.jackson.databind.node.ObjectNode)input.writes().getFirst().value()).put("text","changed");
        assertThrows(IllegalArgumentException.class,()->new FeedbackTransactionPlan(input,p.canonical(),p.sha256()));
        assertEquals("original",p.transaction().writes().getFirst().value().path("text").asText());
        assertEquals(p,new FeedbackTransactionPlan(p.transaction(),p.canonical(),p.sha256()));
    }
    @Test void bindingPinsNamespaceSchemaAndExplicitFieldsWithoutActorSelectors(){
        var b=new FeedbackDataBinding(UUID.randomUUID(),"state",1,Set.of("count","entry"),Set.of("count","entry"));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();assertEquals(b,FeedbackDataBinding.parse(mapper.valueToTree(b.wire())));assertFalse(b.wire().containsKey("actor"));
        assertThrows(IllegalArgumentException.class,()->FeedbackDataBinding.parse(mapper.valueToTree(Map.of("instance_id",b.instanceId(),"namespace","state","schema_version",1,"read_keys",List.of("count"),"write_keys",List.of("count"),"actor","forged"))));
        assertThrows(IllegalArgumentException.class,()->new FeedbackDataBinding(UUID.randomUUID(),"state",1,Set.of("count"),Set.of("secret")));
    }
    @Test void returnedTransactionMustStayInsideTheBindingAndCannotProvideCodeOrIdentity(){
        var b=new FeedbackDataBinding(UUID.randomUUID(),"state",1,Set.of("count","entry"),Set.of("count","entry"));
        String plan="{\"transaction\":{\"schema_version\":1,\"conditions\":[{\"key\":\"count\",\"test\":\"LT\",\"value\":1}],\"writes\":[{\"key\":\"count\",\"op\":\"ADD\",\"value\":1}]}}";
        var p=FeedbackTransactionPlan.parse(b,plan);assertEquals(1,p.transaction().schemaVersion());assertEquals(64,p.sha256().length());
        assertThrows(IllegalArgumentException.class,()->FeedbackTransactionPlan.parse(b,plan.replace("\"key\":\"count\"","\"key\":\"secret\"")));
        assertThrows(IllegalArgumentException.class,()->FeedbackTransactionPlan.parse(b,plan.replace("schema_version\":1","schema_version\":2")));
        assertThrows(IllegalArgumentException.class,()->FeedbackTransactionPlan.parse(b,plan.substring(0,plan.length()-1)+",\"sql\":\"UPDATE anything\"}"));
    }
}
