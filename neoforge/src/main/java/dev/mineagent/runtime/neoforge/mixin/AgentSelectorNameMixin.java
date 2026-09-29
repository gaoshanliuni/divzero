package dev.mineagent.runtime.neoforge.mixin;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** AI display names can contain Unicode. Quoted names retain Brigadier escaping. */
@Mixin(EntitySelectorParser.class)
public abstract class AgentSelectorNameMixin {
    @Redirect(method="parseNameOrUUID",at=@At(value="INVOKE",target="Lcom/mojang/brigadier/StringReader;readString()Ljava/lang/String;"))
    private String divzero$unicodeName(StringReader reader)throws CommandSyntaxException{
        if(!reader.canRead()||StringReader.isQuotedStringStart(reader.peek()))return reader.readString();
        int start=reader.getCursor();while(reader.canRead()&&!Character.isWhitespace(reader.peek()))reader.skip();return reader.getString().substring(start,reader.getCursor());
    }
    @ModifyConstant(method="parseNameOrUUID",constant=@Constant(intValue=16))
    private int divzero$displayNameLength(int original){return 128;}
}
