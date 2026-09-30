package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.*;

/** Named per-AI personas are selected into a draft, then applied through the active-persona CAS. */
final class PersonaLibraryBar {
    private record Choice(String id,String name){@Override public String toString(){return name;}}
    private final String agent;private final UIElement root;private final TextArea editor;private final TextElement status;private final BooleanSupplier current;private final Consumer<String> draft;
    private final Selector<Choice> choices=new Selector<>();private final TextField search=new TextField();private int offset,next=-1;private long epoch;private boolean saving;
    static void add(UIElement parent,String agent,TextArea editor,TextElement status,BooleanSupplier current,Consumer<String> draft){new PersonaLibraryBar(parent,agent,editor,status,current,draft);}
    private PersonaLibraryBar(UIElement parent,String agent,TextArea editor,TextElement status,BooleanSupplier current,Consumer<String> draft){
        this.agent=agent;this.root=parent;this.editor=editor;this.status=status;this.current=current;this.draft=draft;
        var row=WorkspacePanels.row();row.getLayout().height(26);parent.addChild(row);choices.getLayout().flex(1);row.addChild(choices);choices.setCandidates(List.of(new Choice("",t("选择已保存人设"))));choices.setValue(choices.getCandidates().getFirst(),false);choices.setOnValueChanged(choice->{if(choice.id.isEmpty())return;long ticket=++epoch;WorkspacePanels.request("persona.libraryRead",Map.of("kind","get","agentId",agent,"profileId",choice.id)).whenComplete((receipt,error)->{if(!current.getAsBoolean()||ticket!=epoch)return;if(error!=null){WorkspacePanels.failure(status,error);return;}setDraft(WorkspacePanels.state(receipt).get("text").getAsString());status.setText(Component.literal(t("人设已载入草稿，点击应用后生效。")));});});
        row.addChild(NativeUiTheme.button(t("另存人设"),()->Dialog.stringEditorDialog(t("人设名称"),"",name->!name.isBlank()&&name.length()<=128,this::save).show(root)));
        row.addChild(NativeUiTheme.button(t("恢复默认"),()->{setDraft("");status.setText(Component.literal(t("人设已载入草稿，点击应用后生效。")));}));
        var find=WorkspacePanels.row();find.getLayout().height(24);parent.addChild(find);search.getLayout().flex(1);search.textFieldStyle(s->s.placeholder(Component.literal(t("搜索人设"))));find.addChild(search);find.addChild(NativeUiTheme.button(t("搜索"),()->{offset=0;load();}));find.addChild(NativeUiTheme.button("‹",()->{offset=Math.max(0,offset-16);load();}));find.addChild(NativeUiTheme.button("›",()->{if(next>=0){offset=next;load();}}));
        find.addChild(NativeUiTheme.button(t("重命名人设"),this::rename));
        find.addChild(NativeUiTheme.button(t("删除人设"),()->{var selected=choices.getValue();if(selected==null||selected.id.isEmpty())return;Dialog.showCheckBox(t("删除人设"),selected.name,yes->{if(yes)WorkspacePanels.request("persona.libraryWrite",Map.of("kind","delete","agentId",agent,"profileId",selected.id)).whenComplete((receipt,error)->{if(!current.getAsBoolean())return;if(error!=null)WorkspacePanels.failure(status,error);else load();});}).show(root);}));load();
    }
    private void rename(){var selected=choices.getValue();if(selected==null||selected.id.isEmpty()||saving)return;
        WorkspacePanels.request("persona.libraryRead",Map.of("kind","get","agentId",agent,"profileId",selected.id)).whenComplete((receipt,error)->{
            if(!current.getAsBoolean())return;if(error!=null){WorkspacePanels.failure(status,error);return;}var saved=WorkspacePanels.state(receipt);
            Dialog.stringEditorDialog(t("重命名人设"),saved.get("name").getAsString(),name->!name.isBlank()&&name.length()<=128,name->{
                if(saving)return;saving=true;WorkspacePanels.request("persona.libraryWrite",Map.of("kind","rename","agentId",agent,"profileId",selected.id,"expectedRevision",saved.get("revision").getAsString(),"name",name)).whenComplete((value,failure)->{saving=false;if(!current.getAsBoolean())return;if(failure!=null)WorkspacePanels.failure(status,failure);else{status.setText(Component.literal(t("已保存")));load();}});
            }).show(root);
        });
    }
    private void setDraft(String value){draft.accept(value);editor.setValue(value.split("\n",-1),false);}
    private void load(){long ticket=++epoch;WorkspacePanels.request("persona.libraryRead",Map.of("kind","list","agentId",agent,"query",search.getValue(),"offset",Integer.toString(offset))).whenComplete((receipt,error)->{if(!current.getAsBoolean()||ticket!=epoch)return;if(error!=null){WorkspacePanels.failure(status,error);return;}var value=WorkspacePanels.state(receipt);next=value.get("nextOffset").getAsInt();var items=new ArrayList<Choice>();items.add(new Choice("",t("选择已保存人设")));for(var raw:value.getAsJsonArray("items")){var item=raw.getAsJsonObject();items.add(new Choice(item.get("id").getAsString(),item.get("name").getAsString()));}String selected=choices.getValue()==null?"":choices.getValue().id;choices.setCandidates(items);choices.setValue(items.stream().filter(item->item.id.equals(selected)).findFirst().orElse(items.getFirst()),false);});}
    private void save(String name){if(saving)return;saving=true;WorkspacePanels.request("persona.libraryWrite",Map.of("kind","save","agentId",agent,"profileId",UUID.randomUUID().toString(),"name",name,"text",String.join("\n",editor.getValue()))).whenComplete((receipt,error)->{saving=false;if(!current.getAsBoolean())return;if(error!=null)WorkspacePanels.failure(status,error);else{status.setText(Component.literal(t("人设已保存到列表。")));load();}});}
    private static String t(String value){return ClientLanguage.t(value);}
}
