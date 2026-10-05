package dev.mineagent.runtime.legacy189.coremod;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.Name("DivZero189CombatHooks")
@IFMLLoadingPlugin.MCVersion("1.8.9")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"dev.mineagent.runtime.legacy189.coremod."})
public final class LegacyCore implements IFMLLoadingPlugin {
    @Override public String[] getASMTransformerClass() { return new String[] {"dev.mineagent.runtime.legacy189.coremod.CombatTransformer"}; }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) { }
    @Override public String getAccessTransformerClass() { return null; }
}
