package dev.mineagent.runtime.legacy189;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import java.util.*;

/** Stored in the owning save's MapStorage, never in a global production profile. */
public final class NativeWorldData extends WorldSavedData {
    public static final String KEY = "divzero_legacy_world";
    private UUID identity = UUID.randomUUID();
    private String arenaHash = "";
    private boolean modernCombat;
    private final Set<UUID> enabled = new HashSet<UUID>();
    private final Map<UUID, AgentDefinition> agents = new LinkedHashMap<UUID, AgentDefinition>();
    public NativeWorldData() { super(KEY); }
    public NativeWorldData(String key) { super(key); }
    public UUID identity() { return identity; }
    public boolean arenaReady() { return !arenaHash.isEmpty(); }
    public boolean modernCombat() { return modernCombat; }
    public void modernCombat(boolean value) { modernCombat = value; ModernCombat.serverEnabled = value; markDirty(); }
    public String arenaHash() { return arenaHash; }
    public void arena(String hash) { if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("ARENA_HASH"); arenaHash = hash; markDirty(); }
    public boolean enabled(UUID owner) { return enabled.contains(owner); }
    public void enabled(UUID owner, boolean value) { if (value) enabled.add(owner); else enabled.remove(owner); markDirty(); }
    public Collection<AgentDefinition> agents() { return Collections.unmodifiableCollection(agents.values()); }
    public AgentDefinition agent(UUID id) { return agents.get(id); }
    public void put(AgentDefinition value) { agents.put(value.id, value); markDirty(); }
    public void remove(UUID id) { agents.remove(id); markDirty(); }
    public static NativeWorldData get(WorldServer world) {
        NativeWorldData data = (NativeWorldData) world.getMapStorage().loadData(NativeWorldData.class, KEY);
        if (data == null) { data = new NativeWorldData(); world.getMapStorage().setData(KEY, data); data.markDirty(); }
        return data;
    }
    @Override public void readFromNBT(NBTTagCompound nbt) {
        if (nbt.getInteger("schema") != 1) throw new IllegalStateException("LEGACY_WORLD_SCHEMA");
        identity = UUID.fromString(nbt.getString("identity"));
        arenaHash = nbt.getString("arenaHash");
        modernCombat = nbt.getBoolean("modernCombat"); ModernCombat.serverEnabled = modernCombat;
        if (!arenaHash.isEmpty() && !arenaHash.matches("[0-9a-f]{64}")) throw new IllegalStateException("ARENA_HASH");
        enabled.clear(); agents.clear();
        NBTTagList players = nbt.getTagList("enabled", 8);
        for (int i = 0; i < players.tagCount(); i++) enabled.add(UUID.fromString(players.getStringTagAt(i)));
        NBTTagList saved = nbt.getTagList("agents", 10);
        for (int i = 0; i < saved.tagCount(); i++) {
            NBTTagCompound row = saved.getCompoundTagAt(i);
            AgentDefinition value = new AgentDefinition(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("owner")),
                    row.getString("name"), row.getInteger("dimension"), row.getDouble("x"), row.getDouble("y"), row.getDouble("z"));
            if (agents.put(value.id, value) != null) throw new IllegalStateException("LEGACY_AGENT_DUPLICATE");
        }
    }
    @Override public void writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("schema", 1); nbt.setString("identity", identity.toString());
        nbt.setString("arenaHash", arenaHash);
        nbt.setBoolean("modernCombat", modernCombat);
        NBTTagList players = new NBTTagList();
        for (UUID id : enabled) players.appendTag(new net.minecraft.nbt.NBTTagString(id.toString()));
        nbt.setTag("enabled", players);
        NBTTagList saved = new NBTTagList();
        for (AgentDefinition value : agents.values()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", value.id.toString()); row.setString("owner", value.owner.toString()); row.setString("name", value.name);
            row.setInteger("dimension", value.dimension); row.setDouble("x", value.x); row.setDouble("y", value.y); row.setDouble("z", value.z);
            saved.appendTag(row);
        }
        nbt.setTag("agents", saved);
    }
    public static final class AgentDefinition {
        public final UUID id, owner;
        public final String name;
        public int dimension;
        public double x, y, z;
        public AgentDefinition(UUID id, UUID owner, String name, int dimension, double x, double y, double z) {
            if (id == null || owner == null || name == null || name.trim().isEmpty() || name.length() > 48
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("LEGACY_AGENT_DEFINITION");
            this.id = id; this.owner = owner; this.name = name; this.dimension = dimension; this.x = x; this.y = y; this.z = z;
        }
    }
}
