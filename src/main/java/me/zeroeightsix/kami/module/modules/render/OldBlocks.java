package me.zeroeightsix.kami.module.modules.render;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import me.zero.alpine.listener.EventHandler;
import me.zero.alpine.listener.Listener;
import me.zeroeightsix.kami.command.Command;
import me.zeroeightsix.kami.event.events.RenderEvent;
import me.zeroeightsix.kami.module.Module;
import me.zeroeightsix.kami.setting.Setting;
import me.zeroeightsix.kami.setting.Settings;
import me.zeroeightsix.kami.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.util.Tuple;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static me.zeroeightsix.kami.util.ColourUtils.toRGBA;
import static me.zeroeightsix.kami.util.MessageSendHelper.*;

/**
 * @author Haxalicious
 * Search module by wnuke
 * Updated by dominikaaaa on 20/04/20
 * Updated by Afel on 08/06/20
 */
@Module.Info(
        name = "OldBlocks",
        description = "NewChunks but for blocks",
        category = Module.Category.RENDER
)
public class OldBlocks extends Module {
    private final Setting<String> seedString = register(Settings.stringBuilder("Seed").withValue("0"));
    private final Setting<Integer> alpha = register(Settings.integerBuilder("Transparency").withMinimum(1).withMaximum(255).withValue(120).build());
    private final Setting<Integer> chunkDiscardThreshold = register(Settings.integerBuilder("ChunkDiscardThreshold").withMinimum(0).withValue(1000).build());
    private final Setting<Integer> updateInterval = register(Settings.integerBuilder("UpdateInterval").withMinimum(0).withValue(200).build());
    private final Setting<Boolean> tracers = register(Settings.booleanBuilder("Tracers").withValue(true).build());

    public long seed = Long.parseLong(seedString.getValue());
    private IntegratedServer mcServer;

    ScheduledExecutorService exec = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r);
                t.setPriority(Thread.NORM_PRIORITY - 2); //LOW priority
                return t;
            });

    @Override
    public void onEnable() {
        if (chunkDiscardThreshold.getValue() == 0) {
            sendWarningMessage(getChatName() + "ChunkDiscardThreshold is set to 0! This may result in massive lag spikes and/or game freezes!");
        }
        if(seedString.equals("0")) {
            sendErrorMessage(getChatName() + "Seed is not set!");
            sendWarningMessage(getChatName() + "Set the seed with &7" + Command.getCommandPrefix() + "OldBlocks set Seed&f seed");
            disable();
            return;
        }
        seed = Long.parseLong(seedString.getValue());
        YggdrasilAuthenticationService dummyAuth = new YggdrasilAuthenticationService(mc.proxy, UUID.randomUUID().toString());
        MinecraftSessionService dummySession = dummyAuth.createMinecraftSessionService();
        GameProfileRepository dummyProfile = dummyAuth.createProfileRepository();
        PlayerProfileCache gameFolder = new PlayerProfileCache(dummyProfile, new File(mc.gameDir, MinecraftServer.USER_CACHE_FILE.getName()));
        WorldSettings worldSettings = new WorldSettings(seed, GameType.SURVIVAL, false, false, WorldType.DEFAULT);
        mcServer = new IntegratedServer(mc, seedString.getValue(), seedString.getValue(), worldSettings, dummyAuth, dummySession, dummyProfile, gameFolder);
        startTime = 0;
    }

    @Override
    protected void onDisable() {
        mainList.clear();
    }

    private long startTime = 0;
    public static final Map<ChunkPos, Map<BlockPos, Tuple<Integer, Integer>>> mainList = new ConcurrentHashMap<>();


    @EventHandler
    public Listener<ChunkEvent.Load> chunkLoadListener = new Listener<>(event -> {
        if (isEnabled()) {
            if(mc.world != null && mc.player != null) {
                Chunk referenceChunk = event.getChunk();
                ChunkPos tempPos = referenceChunk.getPos();
                Chunk generatedChunk = mcServer.getWorld(mc.player.dimension).getChunkProvider().provideChunk(tempPos.x, tempPos.z);
                final Chunk clientChunk = generatedChunk;
                exec.schedule(() -> {
                    Chunk serverChunk = referenceChunk;
                    ChunkPos pos = serverChunk.getPos();
                    int x = pos.x;
                    int z = pos.z;
                    while (serverChunk.isEmpty()) {
                        serverChunk = mc.world.getChunkProvider().provideChunk(pos.x, pos.z); // Super lazy way to get rid of ghost chunks
                    }
                    compareChunks(serverChunk, clientChunk);
                }, 200, MILLISECONDS);
            }
        }
    });

    @EventHandler
    public Listener<ChunkEvent.Unload> chunkUnloadListener = new Listener<>(event -> {
        if (isEnabled())
            mainList.remove(event.getChunk().getPos());
    });

    Map<BlockPos, Tuple<Integer, Integer>> blocksToShow;

    @Override
    public void onWorldRender(RenderEvent event) {
        if (mainList != null && shouldUpdate()) {
            blocksToShow = mainList.values().stream()
                    .flatMap((e) -> e.entrySet().stream())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        if (blocksToShow != null) {
            GlStateManager.pushMatrix();
            KamiTessellator.prepare(GL11.GL_QUADS);
            for (Map.Entry<BlockPos, Tuple<Integer, Integer>> entry : blocksToShow.entrySet()) {
                KamiTessellator.drawBox(entry.getKey(), entry.getValue().getFirst(), entry.getValue().getSecond());
            }
            KamiTessellator.release();
            GlStateManager.popMatrix();
            GlStateManager.enableTexture2D();

            if (tracers.getValue()) {
                for (Map.Entry<BlockPos, Tuple<Integer, Integer>> entry : blocksToShow.entrySet()) {
                    KamiTessellator.drawLineToBlock(entry.getKey(), entry.getValue().getFirst(), ((float) alpha.getValue()) / 255);
                }
            }
        }
    }

    public void compareChunks(Chunk chunk1, Chunk chunk2) {
        ChunkPos pos = chunk1.getPos();
        BlockPos pos1 = new BlockPos(pos.getXStart(), 0, pos.getZStart());
        BlockPos pos2 = new BlockPos(pos.getXEnd(), 255, pos.getZEnd());
        Iterable<BlockPos> blocks = BlockPos.getAllInBox(pos1, pos2);
        Map<BlockPos, Tuple<Integer, Integer>> found = new HashMap<>();
        int dim = mc.player.dimension;
        try {
            for (BlockPos blockPos : blocks) {
                int side = GeometryMasks.Quad.ALL;
                Block block = chunk1.getBlockState(blockPos).getBlock();
                int block1 = Block.getIdFromBlock(block);
                int block2 = Block.getIdFromBlock(chunk2.getBlockState(blockPos).getBlock()); // Get server-side block
                if(dim == 0) {
                    block1 = mapBlockOverworld(block1);
                    block2 = mapBlockOverworld(block2);
                }
                else if(dim == -1) {
                    block1 = mapBlockNether(block1);
                    block2 = mapBlockNether(block2);
                }
                else {
                    block1 = mapBlockEnd(block1);
                    block2 = mapBlockEnd(block2);
                }

                if (block1 != block2) {
                    //sendChatMessage("Block1: " + String.valueOf(Block.getIdFromBlock(block1)));
                    //sendChatMessage("Block2: " + String.valueOf(block2));
                    //sendChatMessage(blockPos.toString());
                    //sendChatMessage(String.valueOf(block2));
                    Tuple<Integer, Integer> tuple = getTuple(side, block);
                    found.put(blockPos, tuple);
                }
            }
        } catch (NullPointerException ignored) {
        } // Fix ghost chunks getting loaded and generating NullPointerExceptions
        if (chunkDiscardThreshold.getValue() != 0 && found.size() > chunkDiscardThreshold.getValue()) {
            found.clear();
        }
        if (!found.isEmpty()) {
            Map<BlockPos, Tuple<Integer, Integer>> actual = OldBlocks.mainList.computeIfAbsent(pos, (p) -> new ConcurrentHashMap<>());
            actual.clear();
            actual.putAll(found);
        }
    }

    public int mapBlockOverworld(int id) {
        switch(id) {
            case 8:
            case 17:
            case 18:
            case 30:
            case 31:
            case 32:
            case 37:
            case 38:
            case 39:
            case 40:
            case 81:
            case 83:
            case 86:
            case 106:
            case 111:
            case 127:
            case 161:
            case 162:
            case 175:
                id = 0;
                break;
            case 13:
            case 14:
            case 15:
            case 16:
            case 21:
            case 56:
            case 73:
            case 97:
            case 129:
                id = 1;
                break;
        }
        return id;
    }

    public int mapBlockNether(int id) {
        int mappedId;
        switch(id) {
            case 10:
            case 39:
            case 40:
            case 51:
            case 89:
                id = 0;
                break;
            case 88:
            case 153:
            case 213:
                id = 87;
                break;
        }
        return id;
    }

    public int mapBlockEnd(int id) {
        int mappedId;
        switch(id) {
            case 198:
            case 199:
            case 200:
            case 201:
            case 202:
            case 203:
            case 204:
            case 205:
            case 206:
                id = 0;
                break;
        }
        return id;
    }

    private Tuple<Integer, Integer> getTuple(int side, Block block) {
        int blockColor = toRGBA(255, 0, 0, alpha.getValue());
        return new Tuple<>(blockColor, side);
    }

    private long previousTime = 0;

    private boolean shouldUpdate() {
        if (previousTime + 100 <= System.currentTimeMillis()) {
            previousTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    private long compareTime = 0;

    public static class Triplet<T, U, V> {

        private final T first;
        private final U second;
        private final V third;

        public Triplet(T first, U second, V third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }

        public T getFirst() {
            return first;
        }

        public U getSecond() {
            return second;
        }

        public V getThird() {
            return third;
        }
    }
}