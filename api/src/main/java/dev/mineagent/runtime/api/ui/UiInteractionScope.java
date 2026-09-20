package dev.mineagent.runtime.api.ui;
import java.util.Set;

/** Page-local actions are not ScoreView/world permissions. Candidate score previews use their own registry. */
public final class UiInteractionScope {
    public static final Set<String> PAGE=Set.of("ui.observe","ui.act");
    public static final Set<String> SCORE_LAYOUT=Set.of("scoreview.read","scoreview.patch");
    private UiInteractionScope(){}
    public static boolean pageOnly(UiProtocol.Binding binding){return binding!=null&&binding.preview()&&binding.targetObjectId().isEmpty()
            &&binding.entryPath().startsWith("ui/")&&binding.packageRevision()>0&&binding.capabilities().equals(PAGE);}
    public static boolean scoreLayout(UiProtocol.Binding binding){return binding!=null&&!binding.preview()&&!binding.targetObjectId().isBlank()
            &&binding.entryPath().startsWith("ui/")&&binding.capabilities().containsAll(SCORE_LAYOUT);}
    public static boolean localStateWriter(UiProtocol.Session source,java.util.UUID viewer,java.util.UUID pkg,long revision){
        if(source==null||source.status()!=UiProtocol.Status.RENDERED)return false;var b=source.binding();
        return b.viewerPlayerId().equals(viewer)&&b.ownerPackageId().equals(pkg)&&b.packageRevision()==revision&&(pageOnly(b)||scoreLayout(b))
                &&(b.actorKind()==UiProtocol.ActorKind.PLAYER?b.actorId().equals(viewer)&&b.taskId()==null:b.taskId()!=null&&b.taskRevision()>0);
    }
}
