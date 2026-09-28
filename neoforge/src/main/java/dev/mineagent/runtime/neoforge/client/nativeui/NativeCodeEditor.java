package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Cursor;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.codeeditor.CodeEditor;
import com.lowdragmc.lowdraglib2.gui.ui.elements.codeeditor.language.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.*;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import dev.mineagent.runtime.neoforge.client.language.ClientLanguage;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** LDLib2 source editor, with native IME, real syntax colors, line numbers and local editing history. */
final class NativeCodeEditor extends CodeEditor {
    private static final LanguageDefinition JAVA=new LanguageDefinition("Java",List.of(TokenTypes.KEYWORD.createTokenType(List.of("abstract","assert","boolean","break","byte","case","catch","char","class","const","continue","default","do","double","else","enum","exports","extends","false","final","finally","float","for","if","implements","import","instanceof","int","interface","long","module","native","new","null","package","private","protected","public","record","return","sealed","short","static","strictfp","super","switch","synchronized","this","throw","throws","transient","true","try","var","void","volatile","while","yield")),TokenTypes.STRING,TokenTypes.COMMENT,TokenTypes.NUMBER,TokenTypes.IDENTIFIER,TokenTypes.OPERATOR,TokenTypes.WHITESPACE,TokenTypes.OTHER),Set.of("{"));
    private boolean readOnly,tabIndent;
    NativeCodeEditor(String language){setLanguage(language.equals("JAVA")?JAVA:Languages.JAVASCRIPT);getLayout().widthPercent(100).flex(1);contentView.getLayout().paddingLeft(0);var gutter=new UIElement(){@Override protected void drawBackgroundAdditional(com.lowdragmc.lowdraglib2.gui.ui.rendering.IGUIContext raw){if(!(raw instanceof GUIContext context))return;float y=contentView.getContentY();float height=contentView.getContentHeight();int first=Math.max(0,(int)Math.floor(getScrollY()/lineHeight()));int last=Math.min(NativeCodeEditor.this.getValue().length-1,first+(int)Math.ceil(height/lineHeight()));context.enableScissor(getContentX(),y,getContentWidth(),height);try{for(int line=first;line<=last;line++)com.lowdragmc.lowdraglib2.client.font.LDFonts.drawText(context,com.lowdragmc.lowdraglib2.client.font.LDFonts.font(),Component.literal(Integer.toString(line+1)),getContentX()+3,y+line*lineHeight()-getScrollY(),0xffaaaaaa,false);}finally{context.disableScissor();}}};gutter.getLayout().width(24).heightPercent(100).flexShrink(0);gutter.getStyle().backgroundTexture(new com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture(0xff171717));contentView.getParent().addChildAt(gutter,0);}
    void language(String value){setLanguage(value.equals("JAVA")?JAVA:Languages.JAVASCRIPT);}
    void load(String source){setValue(source.split("\n",-1),false);pushHistory();}
    String source(){return String.join("\n",getValue());}
    void readOnly(boolean value){readOnly=value;}
    @Override public boolean isEditable(){return !readOnly&&super.isEditable();}
    @Override public boolean ownsKey(UIEvent event){return tabIndent&&event.keyCode==GLFW.GLFW_KEY_TAB||super.ownsKey(event);}
    @Override protected void onKeyDown(UIEvent event){if(event.keyCode==GLFW.GLFW_KEY_TAB&&!tabIndent)return;if(event.keyCode==GLFW.GLFW_KEY_TAB)event.stopPropagation();super.onKeyDown(event);}
    private void focusEditor(){if(getModularUI()!=null)getModularUI().requestFocus(this);}
    private Cursor at(int offset){String text=source();int line=0,col=0;for(int i=0;i<Math.min(offset,text.length());i++){if(text.charAt(i)=='\n'){line++;col=0;}else col++;}return new Cursor(line,col);}
    private int offset(){var cursor=cursorPos();int total=0;String[] lines=getValue();for(int i=0;i<cursor.line();i++)total+=lines[i].length()+1;return total+cursor.col();}
    private static int locate(String source,String query,int from,boolean sensitive){for(int i=Math.max(0,from);i<=source.length()-query.length();i++)if(source.regionMatches(!sensitive,i,query,0,query.length()))return i;return -1;}
    boolean find(String query,boolean sensitive){if(query.isEmpty()||query.length()>512)return false;String source=source();int found=locate(source,query,offset(),sensitive);if(found<0)found=locate(source,query,0,sensitive);if(found<0)return false;focusEditor();var start=at(found);var end=at(found+query.length());setCursor(end.line(),end.col());setSelection(start,end);return true;}
    int replace(String query,String replacement,boolean sensitive,boolean all){if(readOnly||query.isEmpty()||query.length()>512)return 0;String source=source();var out=new StringBuilder();int index=0,count=0;while(index<source.length()){int found=locate(source,query,index,sensitive);if(found<0)break;out.append(source,index,found).append(replacement);index=found+query.length();count++;if(!all)break;}if(count==0)return 0;out.append(source,index,source.length());pushHistory();setValue(out.toString().split("\n",-1),true);focusEditor();return count;}
    void goTo(int line){int count=getValue().length;if(line<1||line>count)throw new IllegalArgumentException("LINE_OUT_OF_RANGE");focusEditor();setCursor(line-1,0);collapseSelectionToCursor();}
    private void history(String command){if(readOnly)return;focusEditor();var event=UIEvent.create(UIEvents.EXECUTE_COMMAND);event.command=command;onExecuteCommand(event);}
    UIElement tools(){var root=new UIElement();root.getLayout().widthPercent(100).gapAll(3);var search=WorkspacePanels.row();search.getLayout().height(24);root.addChild(search);var query=new TextField();query.getLayout().width(0).minWidth(45).flex(1);query.textFieldStyle(s->s.placeholder(Component.literal(t("查找"))));search.addChild(query);var replacement=new TextField();replacement.getLayout().width(0).minWidth(45).flex(1);replacement.textFieldStyle(s->s.placeholder(Component.literal(t("替换为"))));search.addChild(replacement);var sensitive=new Toggle().setText(t("区分大小写"));sensitive.getLayout().width(82).flexShrink(0);sensitive.setOn(true,false);search.addChild(sensitive);var status=WorkspacePanels.text("");root.addChild(status);search.addChild(NativeUiTheme.button(t("查找下一个"),()->status.setText(Component.literal(find(query.getValue(),sensitive.isOn())?"":t("未找到")))));search.addChild(NativeUiTheme.button(t("全部替换"),()->status.setText(Component.literal(t("已替换")+" "+replace(query.getValue(),replacement.getValue(),sensitive.isOn(),true)))));
        var commands=WorkspacePanels.row();commands.getLayout().height(24);root.addChild(commands);commands.addChild(NativeUiTheme.button(t("撤销编辑"),()->history(CommandEvents.UNDO)));commands.addChild(NativeUiTheme.button(t("重做编辑"),()->history(CommandEvents.REDO)));var line=new TextField().setText("1",false);line.getLayout().width(45);commands.addChild(line);commands.addChild(NativeUiTheme.button(t("跳转行"),()->{try{goTo(Integer.parseInt(line.getValue()));}catch(Exception error){status.setText(Component.literal(t("行号无效")));}}));var tab=new Toggle().setText(t("Tab 缩进"));tab.registerValueListener(value->tabIndent=value);commands.addChild(tab);return root;
    }

    private static String t(String value){return ClientLanguage.t(value);}
}
