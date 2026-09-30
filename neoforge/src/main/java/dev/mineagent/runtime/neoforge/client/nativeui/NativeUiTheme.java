package dev.mineagent.runtime.neoforge.client.nativeui;

import com.lowdragmc.lowdraglib2.gui.texture.*;
import com.lowdragmc.lowdraglib2.gui.ui.*;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.style.*;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.MCSprites;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Minecraft is the explicit default theme for built-ins and generated UI. */
public final class NativeUiTheme {
    public static final int TEXT=0xff262626,MUTED=0xff4a4a4a,ACCENT=0xff264b20;
    public static String state(String code){return dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(switch(code){case "READY"->"就绪";case "IDLE"->"待命";case "STREAMING"->"媒体传输中";case "APPLIED"->"已应用";case "ACCEPTED"->"已受理";case "STARTED","RUNNING"->"运行中";case "STOPPED"->"已停止";case "CANCELLED"->"已取消";case "FAILED"->"失败";case "COMPLETED","FINISHED"->"已完成";case "PUBLISHED"->"已发布";case "GENERATING"->"生成中";case "SAMPLING"->"勘测中";case "EXPIRED"->"已过期";case "CREATOR"->"创造模式";case "SURVIVAL"->"生存模式";case "NOT_LOADED","UNLOADED"->"身体未加载";case "PLANNED"->"待施工";case "PREPARING"->"准备中";case "APPLYING"->"施工中";case "VERIFIED"->"验证通过";case "UNVERIFIED"->"尚未验证";case "PAUSED"->"已暂停";case "UNDONE"->"已撤销";case "PARTIAL"->"部分完成";case "CONFLICT"->"发生冲突";case "UNKNOWN"->"结果待核对";case "REJECTED"->"已拒绝";case "EMPTY"->"尚无计划";default->code;});}
    /** Only authored enum/config selectors opt in; model IDs and user-defined labels stay literal. */
    public static void options(Selector<String> selector){selector.setCandidateUIProvider(value->new TextElement().setText(Component.literal(option(value))));}
    public static String option(String value){if(value==null)return "";return dev.mineagent.runtime.neoforge.client.language.ClientLanguage.t(switch(value){
        case "BLOCK"->"方块";case "ITEM"->"物品";case "ENTITY"->"实体";case "PROJECTILE"->"投射物";case "MACHINE"->"机器";case "GUI"->"界面";case "WORLD_BOARD"->"世界面板";case "RULE"->"规则";case "SKILL"->"技能";case "ADAPTER"->"适配器";case "OTHER"->"其它";case "ALL"->"全部";case "ACTIVE"->"进行中";case "ARCHIVED"->"已归档";case "HOT"->"当前内容";case "RUNNING"->"运行中";case "WAITING_FOR_PLAYER"->"等待玩家";case "FAILED"->"失败";case "COMPLETED","FINISHED"->"已完成";case "CANCELLED"->"已取消";case "EXPIRED"->"已过期";
        case "RECORD_ONLY"->"仅记录";case "AGENT_WAKE"->"唤醒 AI";case "SCRIPT"->"脚本";case "STATE_PUSH"->"推送状态";case "GENERAL"->"通用任务";
        case "GENERATION"->"生成内容";case "UI_PACKAGE"->"界面内容包";case "WORLD_CONTENT"->"世界内容";case "UI_PATCH"->"修改界面";case "WORLD_PATCH"->"修改世界";case "LINK"->"关联内容";case "SERVER"->"服务端";case "CLIENT"->"客户端";
        case "SCREEN"->"原生界面";case "HUD"->"常驻 HUD";case "SCREEN_OVERLAY"->"菜单附加层";case "ENTITY_HUD"->"实体悬浮层";case "high"->"高";case "medium"->"中";case "low"->"低";case "off","none"->"关闭";case "auto"->"自动";case "Provider"->"模型服务";case "Runtime"->"运行设置";case "Security"->"权限与安全";case "Speech"->"语音";case "Appearance"->"外观";case "Budget"->"用量预算";case "Memory"->"记忆";case "World"->"世界";case "Agents"->"AI 玩家";case "Tools"->"工具";
        case "openai-compatible,ollama"->"兼容 API 优先，本机模型备用";case "ollama,openai-compatible"->"本机模型优先，兼容 API 备用";default->state(value);
    });}
    private NativeUiTheme(){}
    public static Stylesheet mc(){return StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC);}
    public static UI ui(UIElement root){root.addClass(NativeButtonFeedback.ROOT_CLASS);return UI.of(root,List.of(mc()),size->size);}
    public static IGuiTexture panel(){return MCSprites.RECT.copy().setColor(0xedffffff);}
    public static IGuiTexture inset(){return MCSprites.RECT_5.copy().setColor(0xedffffff);}
    public static IGuiTexture title(){return MCSprites.RECT_BORDER.copy().setColor(0xf5ffffff);}
    public static <T extends UIElement> T card(T element){element.getStyle().backgroundTexture(panel());element.getLayout().paddingAll(9);return element;}
    public static Button button(String text,Runnable click){
        var button=new Button().setText(Component.literal(text));button.getLayout().height(23).paddingHorizontal(8).marginRight(5);
        button.text.textStyle(s->s.fontSize(9));button.setOnClick(event->click.run());return button;
    }
    public static Button iconButton(String glyph,Runnable action){
        var button=button(glyph,action);button.getLayout().width(23).height(23).minWidth(23).maxWidth(23).minHeight(23).maxHeight(23).flexGrow(0).flexShrink(0).paddingAll(0).marginAll(0).alignSelf(dev.vfyjxf.taffy.style.AlignItems.CENTER);
        button.text.getLayout().marginAll(0);button.text.textStyle(style->style.fontSize(10));return button;
    }
    public static TextElement text(String value,int color,float size){var text=new TextElement().setText(Component.literal(value));text.textStyle(s->s.textColor(color).textShadow(false).fontSize(size).textWrap(TextWrap.WRAP).adaptiveWidth(false).adaptiveHeight(true));text.getLayout().widthPercent(100);return text;}
    /** Preserve MC stylesheet textures; only set content-specific typography and empty placeholders. */
    public static void controls(UIElement root){
        if(root instanceof Button button)NativeButtonFeedback.install(button);
        if(!root.hasClass("divzero-control-style")){
            root.addClass("divzero-control-style");
            if(root instanceof TextField field){field.textFieldStyle(s->s.textColor(0xffffffff));if(field.getTextFieldStyle().placeholder().getString().equals("Empty"))field.textFieldStyle(s->s.placeholder(Component.empty()));}
            else if(root instanceof TextArea area)area.textAreaStyle(s->s.textColor(0xffffffff).placeholder(Component.empty()));
            else if(root instanceof ProgressBar progress)progress.bar.getStyle().backgroundTexture(new ColorRectTexture(0xff6d9b31));
        }
        for(var child:root.getChildren())controls(child);
    }
}
