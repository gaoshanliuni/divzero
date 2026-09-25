package dev.mineagent.runtime.neoforge.client.cinematic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit local filming mode. Captures the game framebuffer, never the desktop.
 * The render-only camera pose leaves the actual player as the input owner. */
@EventBusSubscriber(modid = "mineagent_runtime", value = Dist.CLIENT)
public final class CinematicCaptureClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FPS = 30;
    private static final ArrayBlockingQueue<Frame> QUEUE = new ArrayBlockingQueue<>(3);
    private static final AtomicBoolean COPYING = new AtomicBoolean();
    private static volatile boolean closing, failed;
    private static volatile String error = "";
    private static volatile long frames, dropped;
    private static JsonNode plan;
    private static Path root;
    public record CameraPose(Vec3 position, float yaw, float pitch) {}
    private static volatile CameraPose desiredPose;
    public static CameraPose cameraPose() { return enabled() ? desiredPose : null; }
    private static Process encoder;
    private static Thread writer;
    private static long started, lastCapture, stopAt, shotAt;
    private static String marker = "";
    private static Vec3 smoothed;
    private static boolean initialTrustHandled;
    private static long previewAt;
    private record Frame(long index, byte[] rgba) {}

    public static boolean enabled() { return Boolean.getBoolean("mineagent.cinematic"); }
    /** Existing fixture callers retain their original stop behavior outside filming mode. */
    public static void finish() {
        var mc = Minecraft.getInstance();
        if (!enabled() || failed || started == 0) { mc.stop(); return; }
        if (stopAt == 0) stopAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
    }

    private static Object fixtureField(JsonNode shot, String name) throws Exception {
        String type = shot.path("fixture").asText(plan.path("fixture").asText());
        if (!type.startsWith("dev.mineagent.runtime.neoforge.ui.") || !type.contains("Smoke"))
            throw new IllegalArgumentException("CINEMATIC_FIXTURE_ONLY");
        var field = Class.forName(type).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Vec3 vector(JsonNode value, Vec3 fallback) {
        return value.isArray() && value.size() == 3
                ? new Vec3(value.get(0).asDouble(), value.get(1).asDouble(), value.get(2).asDouble()) : fallback;
    }

    private static void start(Minecraft mc) throws Exception {
        root = Files.createDirectories(mc.gameDirectory.toPath().resolve("cinematic"));
        plan = JSON.readTree(Files.readString(mc.gameDirectory.toPath().resolve("cinematic.json")));
        int width = mc.getWindow().getWidth(), height = mc.getWindow().getHeight();
        if (width != 1920 || height != 1080) throw new IllegalStateException("CINEMATIC_EXPECTED_1920x1080_" + width + "x" + height);
        String ffmpeg = System.getProperty("mineagent.cinematic.ffmpeg", "");
        if (!Path.of(ffmpeg).isAbsolute() || !Files.isRegularFile(Path.of(ffmpeg))) throw new IllegalStateException("CINEMATIC_FFMPEG_REQUIRED");
        var command = List.of(ffmpeg, "-hide_banner", "-loglevel", "warning", "-y", "-f", "rawvideo", "-pixel_format", "rgba",
                "-video_size", width + "x" + height, "-framerate", "30", "-i", "pipe:0", "-an", "-c:v", "h264_nvenc",
                "-preset", "p5", "-cq", "18", "-b:v", "0", "-pix_fmt", "yuv420p", "-movflags", "+faststart", root.resolve("raw.mp4").toString());
        encoder = new ProcessBuilder(command).redirectError(root.resolve("encoder.log").toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        started = shotAt = System.nanoTime();
        writer = Thread.ofPlatform().name("cinematic-video-writer").daemon(true).start(() -> {
            byte[] previous = null;
            try (OutputStream pipe = encoder.getOutputStream()) {
                while (!closing || !QUEUE.isEmpty()) {
                    Frame frame = QUEUE.poll(100, TimeUnit.MILLISECONDS);
                    if (frame == null) continue;
                    if (previous == null) previous = frame.rgba();
                    while (frames < frame.index()) { pipe.write(previous); frames++; }
                    pipe.write(frame.rgba()); frames++; previous = frame.rgba();
                }
            } catch (Exception e) { error = e.toString(); failed = true; }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(CinematicCaptureClient::close, "cinematic-finalize"));
        Files.writeString(root.resolve("started.json"), JSON.writeValueAsString(Map.of("width",width,"height",height,"fps",FPS,"source","Minecraft framebuffer","camera","render-only pose; original player input owner")));
    }

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!enabled() || failed) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.hasSingleplayerServer()) return;
        try {
            if (started == 0) start(mc);
            if (!initialTrustHandled && mc.screen instanceof dev.mineagent.runtime.neoforge.client.screen.ControlCenterScreen screen) {
                for (var child : List.copyOf(screen.children())) {
                    if (child instanceof net.minecraft.client.gui.components.Button button && button.active
                            && button.getMessage().getString().contains("信任此服务器")) {
                        button.onPress(new net.minecraft.client.input.KeyEvent(257,0,0));
                        initialTrustHandled=true; mc.setScreen(null); mc.player.connection.sendCommand("ai accept");
                        break;
                    }
                }
            }
            if (stopAt != 0 && System.nanoTime() >= stopAt) { close(); mc.stop(); return; }
            String current = "scene";
            if (plan.has("phaseField")) current = String.valueOf(fixtureField(plan, plan.get("phaseField").asText()));
            JsonNode shot = plan;
            for (var candidate : plan.path("shots")) if (current.matches(candidate.path("phase").asText(".*"))) { shot = candidate; break; }
            if (!marker.equals(current)) {
                marker = current; shotAt = System.nanoTime();
                Files.writeString(root.resolve("markers.jsonl"), JSON.writeValueAsString(Map.of("seconds",(shotAt-started)/1e9,"phase",current)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (shot.path("view").asText("orbit").equals("native")) {
                desiredPose=null;
                mc.setCameraEntity(mc.player); mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.options.hideGui = false; smoothed = null; return;
            }
            mc.options.hideGui = !shot.path("hud").asBoolean(false);
            mc.setCameraEntity(mc.player);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            mc.options.fov().set(shot.path("fov").asInt(50));
            Vec3 center = vector(shot.path("center"), mc.player.position().add(0, 1, 0));
            if (shot.has("centerFields")) {
                var names = shot.get("centerFields");
                center = new Vec3(((Number)fixtureField(shot,names.get(0).asText())).doubleValue(),((Number)fixtureField(shot,names.get(1).asText())).doubleValue(),((Number)fixtureField(shot,names.get(2).asText())).doubleValue());
            }
            if (shot.has("entityField")) {
                Object id = fixtureField(shot,shot.get("entityField").asText());
                if (id instanceof List<?> list) { try { id=list.getFirst(); } catch(IndexOutOfBoundsException ignored) { id=null; } }
                if (id instanceof Entity entity) id=entity.getUUID();
                if (id instanceof UUID uuid) for (Entity entity : mc.level.entitiesForRendering()) if (entity.getUUID().equals(uuid)) {
                    center = entity.position().add(0,shot.path("targetHeight").asDouble(1),0); break;
                }
            }
            double elapsed = (System.nanoTime() - shotAt) / 1e9;
            double angle = Math.toRadians(shot.path("angle").asDouble(25) + Math.sin(elapsed / shot.path("period").asDouble(18)) * shot.path("arc").asDouble(20));
            double radius = shot.path("radius").asDouble(9), elevation = shot.path("height").asDouble(3);
            Vec3 position = center.add(Math.sin(angle)*radius,elevation,Math.cos(angle)*radius);
            if (smoothed == null || smoothed.distanceTo(position)>35) smoothed=position;
            else smoothed=smoothed.add(position.subtract(smoothed).scale(.08));
            Vec3 direction=center.subtract(smoothed);
            float yaw=(float)Math.toDegrees(Math.atan2(-direction.x,direction.z));
            float pitch=(float)-Math.toDegrees(Math.atan2(direction.y,Math.sqrt(direction.x*direction.x+direction.z*direction.z)));
            desiredPose=new CameraPose(smoothed,yaw,pitch);
        } catch(Exception e) { fail(e); }
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if(!enabled() || started==0 || closing || failed) return;
        long now=System.nanoTime();
        if(now-lastCapture<1_000_000_000L/FPS || !COPYING.compareAndSet(false,true)) return;
        lastCapture=now;long index=Math.round((now-started)/1e9*FPS);
        try {
            var mc=Minecraft.getInstance();
            Screenshot.takeScreenshot(mc.getMainRenderTarget(),image->{
                try(image){
                    if (index-previewAt>=FPS*5) { image.writeToFile(root.resolve("preview.png")); previewAt=index; }
                    int[] pixels=image.getPixelsABGR();
                    byte[] bytes=new byte[pixels.length*4];
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels);
                    if(!QUEUE.offer(new Frame(index,bytes))) dropped++;
                } catch(Exception e){fail(e);} finally {COPYING.set(false);}
            });
        } catch(Exception e) {COPYING.set(false);fail(e);}
    }

    private static void fail(Exception e){failed=true;error=e.toString();try{if(root!=null)Files.writeString(root.resolve("failure.txt"),error);}catch(Exception ignored){}}
    private static synchronized void close(){
        if(closing)return;closing=true;
        try{
            if(writer!=null)writer.join(15000);
            if(encoder!=null&&!encoder.waitFor(20,TimeUnit.SECONDS)){encoder.destroy();error="ENCODER_FINALIZE_TIMEOUT";}
            if(root!=null)Files.writeString(root.resolve("recording.json"),JSON.writeValueAsString(Map.of("frames",frames,"fps",FPS,"droppedCaptureRequests",dropped,"error",error,"encoderExit",encoder==null||encoder.isAlive()?-1:encoder.exitValue())));
        }catch(Exception e){fail(e);}
    }
    private CinematicCaptureClient(){}
}
