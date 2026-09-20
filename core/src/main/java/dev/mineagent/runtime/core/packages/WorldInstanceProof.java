package dev.mineagent.runtime.core.packages;
import dev.mineagent.runtime.api.packages.*;
import java.util.UUID;
/** Independent identity plus live native evidence; publication or the persisted ACTIVE string is insufficient. */
public final class WorldInstanceProof {
    private WorldInstanceProof(){}
    public static boolean matches(WorldActivationLedger.Activation a,RuntimePackage p,RuntimeInstance i,UUID world,UUID owner,boolean live,int minimum,int actualBlocks){
        return matches(a,p,i,world,owner,live,minimum,actualBlocks,0,0);
    }
    public static boolean matches(WorldActivationLedger.Activation a,RuntimePackage p,RuntimeInstance i,UUID world,UUID owner,boolean live,int minimum,int actualBlocks,int minimumObjects,int actualObjects){
        return minimum>=0&&minimum<=128&&minimumObjects>=0&&minimumObjects<=32&&minimum+minimumObjects>=1&&actualBlocks>=minimum&&actualObjects>=minimumObjects&&identity(a,p,i,world,owner,live);
    }
    /** Proves only a live RULE identity. A separate, authorized data read is required for task completion. */
    public static boolean matchesRule(WorldActivationLedger.Activation a,RuntimePackage p,RuntimeInstance i,UUID world,UUID owner,boolean live,UUID definition){
        return identity(a,p,i,world,owner,live)&&a.definitionId().equals(definition)&&p.definitions().get(definition).kind()==RuntimeDefinitionKind.RULE;
    }
    private static boolean identity(WorldActivationLedger.Activation a,RuntimePackage p,RuntimeInstance i,UUID world,UUID owner,boolean live){
        if(!live||a==null||p==null||i==null||!p.enabled()||!a.state().equals("ACTIVE")
                ||!a.owner().equals(owner)||!a.worldId().equals(world)||!a.packageId().equals(p.packageId())||!a.canonicalSha256().equals(p.canonicalSha256())||p.revision()<a.packageRevision())return false;
        var d=p.definitions().get(a.definitionId());
        return d!=null&&i.worldId().equals(world)&&i.instanceId().equals(a.instanceId())&&i.packageId().equals(a.packageId())&&i.definitionId().equals(a.definitionId())
                &&i.definitionRevision()==d.revision()&&i.stateSchemaVersion()==d.stateSchemaVersion()&&i.location().equals(a.location());
    }
}
