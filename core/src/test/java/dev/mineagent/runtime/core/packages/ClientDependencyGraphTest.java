package dev.mineagent.runtime.core.packages;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientDependencyGraphTest {
    @Test void acceptsMixedClosureAndBindsLanguageAndRuntime(){UUID root=UUID.randomUUID(),rhino=UUID.randomUUID(),java=UUID.randomUUID();var j=node(java,ClientDependencyGraph.Language.JAVA,"fixture.Extension",Map.of());var r=node(rhino,ClientDependencyGraph.Language.RHINO,"client/main.js",Map.of(java,"1"));var graph=new ClientDependencyGraph(1,root,Map.of(rhino,"1"),List.of(r,j));assertEquals(Set.of(rhino,java),new HashSet<>(graph.nodes().stream().map(ClientDependencyGraph.Node::packageId).toList()));assertTrue(graph.receiptHash().startsWith("dependencies-v2:"));assertEquals(graph.receiptHash(),new ClientDependencyGraph(1,root,Map.of(rhino,"1"),List.of(j,r)).receiptHash());}
    @Test void rejectsCrossLanguageCycleAndVersionMismatch(){UUID root=UUID.randomUUID(),rhino=UUID.randomUUID(),java=UUID.randomUUID();var r=node(rhino,ClientDependencyGraph.Language.RHINO,"client/main.js",Map.of(java,"1"));var j=node(java,ClientDependencyGraph.Language.JAVA,"fixture.Extension",Map.of(rhino,"1"));assertThrows(IllegalArgumentException.class,()->new ClientDependencyGraph(1,root,Map.of(rhino,"1"),List.of(r,j)));assertThrows(IllegalArgumentException.class,()->new ClientDependencyGraph(1,root,Map.of(rhino,"2"),List.of(node(rhino,ClientDependencyGraph.Language.RHINO,"client/main.js",Map.of()))));}
    private static ClientDependencyGraph.Node node(UUID id,ClientDependencyGraph.Language language,String entry,Map<UUID,String> dependencies){return new ClientDependencyGraph.Node(id,"1",1,"a".repeat(64),id,1,language,"b".repeat(64),"c".repeat(64),entry,dependencies);}
}
