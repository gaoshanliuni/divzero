package dev.mineagent.runtime.legacy189;

/** No client classes are linked by the dedicated-server bootstrap. */
public class CommonProxy {
    public void initialize() { }
    public void registerModels() { }
    public void receive(NativeNetwork.State message) { }
    public void bowUse(NativeNetwork.BowUse message) { }
    public void duelState(String json) { }
    public Object equipmentScreen(int id, net.minecraft.entity.player.EntityPlayer player) { return null; }
}
