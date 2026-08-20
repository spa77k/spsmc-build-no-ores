package dev.spa.buildnoores;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 建築ワールドだけから自然生成鉱石を除去するPaperプラグインです。
 */
public final class SpsmcBuildNoOresPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final String TARGET_WORLD = "build";
    private static final String SCAN_COMPLETED_PATH = "migration.existing-build-scan-completed";
    private static final int REGION_HEADER_BYTES = 8192;
    private static final int REGION_CHUNK_COUNT = 32;
    private static final int CHUNKS_PER_TICK = 1;
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private static final Set<Material> ORE_MATERIALS = Set.of(
            Material.COAL_ORE,
            Material.DEEPSLATE_COAL_ORE,
            Material.COPPER_ORE,
            Material.DEEPSLATE_COPPER_ORE,
            Material.IRON_ORE,
            Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE,
            Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE,
            Material.DEEPSLATE_REDSTONE_ORE,
            Material.EMERALD_ORE,
            Material.DEEPSLATE_EMERALD_ORE,
            Material.LAPIS_ORE,
            Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE,
            Material.DEEPSLATE_DIAMOND_ORE
    );

    private ExistingOreScanJob existingOreScanJob;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("buildnores");
        if (command == null) {
            getLogger().severe("buildnoresコマンドを登録できませんでした。plugin.ymlを確認してください。");
        } else {
            command.setExecutor(this);
        }

        // build がプラグイン有効化より先に読み込まれていた場合にも登録します。
        for (World world : Bukkit.getWorlds()) {
            registerPopulator(world);
        }
    }

    @Override
    public void onDisable() {
        if (existingOreScanJob != null) {
            existingOreScanJob.cancel();
            existingOreScanJob = null;
        }
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        // Multiverse-Core が後から build を作成する場合はこちらで登録します。
        registerPopulator(event.getWorld());
    }

    private void registerPopulator(World world) {
        if (!TARGET_WORLD.equals(world.getName()) || world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }

        List<BlockPopulator> populators = world.getPopulators();
        if (populators.stream().anyMatch(OreRemovalPopulator.class::isInstance)) {
            return;
        }

        // WorldInitEvent 中に追加し、以後の新規チャンク生成へ適用します。
        populators.add(new OreRemovalPopulator());
        getLogger().info("buildワールドの新規チャンクから自然生成鉱石を除去します。");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("buildnores")) {
            return false;
        }

        if (args.length != 1 || !args[0].equalsIgnoreCase("scan")) {
            sender.sendMessage("使い方: /buildnores scan");
            return true;
        }

        if (!sender.hasPermission("spsmcbuildnores.scan")) {
            sender.sendMessage("このコマンドを実行する権限がありません。");
            return true;
        }

        if (existingOreScanJob != null) {
            sender.sendMessage("既存buildワールドの鉱石スキャンはすでに実行中です。");
            return true;
        }

        if (getConfig().getBoolean(SCAN_COMPLETED_PATH, false)) {
            sender.sendMessage("既存buildワールドの鉱石スキャンは完了済みです。再実行しません。");
            return true;
        }

        World buildWorld = Bukkit.getWorld(TARGET_WORLD);
        if (buildWorld == null) {
            sender.sendMessage("buildワールドが読み込まれていません。");
            return true;
        }

        if (buildWorld.getEnvironment() != World.Environment.NORMAL) {
            sender.sendMessage("buildワールドが通常環境ではないため、スキャンを中止しました。");
            return true;
        }

        List<ChunkCoordinate> chunks;
        try {
            chunks = ExistingChunkIndex.read(buildWorld);
        } catch (IOException exception) {
            getLogger().log(java.util.logging.Level.SEVERE, "buildワールドの既存チャンク一覧を読み取れませんでした。", exception);
            sender.sendMessage("buildワールドのチャンク一覧を読み取れませんでした。ログを確認してください。");
            return true;
        }

        existingOreScanJob = new ExistingOreScanJob(buildWorld, chunks, sender);
        existingOreScanJob.start();
        sender.sendMessage("既存buildワールドの鉱石スキャンを開始しました。完了までサーバーを停止しないでください。");
        return true;
    }

    private void finishExistingOreScan(ExistingOreScanJob job) {
        if (existingOreScanJob != job) {
            return;
        }

        getConfig().set(SCAN_COMPLETED_PATH, true);
        saveConfig();
        existingOreScanJob = null;
        job.sendMessage("既存buildワールドの鉱石スキャンが完了しました。対象チャンク: "
                + job.totalChunks() + ", スキャン済み: " + job.scannedChunks()
                + ", 置換ブロック: " + job.replacedBlocks() + "。この処理は再実行されません。");
    }

    private void failExistingOreScan(ExistingOreScanJob job, Exception exception) {
        if (existingOreScanJob != job) {
            return;
        }

        existingOreScanJob = null;
        getLogger().log(java.util.logging.Level.SEVERE, "既存buildワールドの鉱石スキャンに失敗しました。完了扱いにはしません。", exception);
        job.sendMessage("既存buildワールドの鉱石スキャンに失敗しました。完了扱いではないため、原因確認後に再実行できます。");
    }

    private static int replaceOresInChunk(Chunk chunk) {
        int replaced = 0;
        World world = chunk.getWorld();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    Material material = chunk.getBlock(x, y, z).getType();
                    Material replacement = replacementFor(material);
                    if (replacement != null) {
                        chunk.getBlock(x, y, z).setType(replacement, false);
                        replaced++;
                    }
                }
            }
        }
        return replaced;
    }

    private static Material replacementFor(Material material) {
        if (!ORE_MATERIALS.contains(material)) {
            return null;
        }
        return material.name().startsWith("DEEPSLATE_") ? Material.DEEPSLATE : Material.STONE;
    }

    private record ChunkCoordinate(int x, int z) {
    }

    private static final class ExistingChunkIndex {
        private ExistingChunkIndex() {
        }

        private static List<ChunkCoordinate> read(World world) throws IOException {
            Path regionDirectory = world.getWorldFolder().toPath().resolve("region");
            if (!Files.isDirectory(regionDirectory)) {
                return List.of();
            }

            List<ChunkCoordinate> chunks = new ArrayList<>();
            try (Stream<Path> files = Files.list(regionDirectory)) {
                List<Path> regionFiles = files
                        .filter(Files::isRegularFile)
                        .filter(path -> REGION_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();

                for (Path regionFile : regionFiles) {
                    Matcher matcher = REGION_FILE_PATTERN.matcher(regionFile.getFileName().toString());
                    if (!matcher.matches()) {
                        continue;
                    }
                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    readRegionHeader(regionFile, regionX, regionZ, chunks);
                }
            }
            return chunks;
        }

        private static void readRegionHeader(
                Path regionFile,
                int regionX,
                int regionZ,
                List<ChunkCoordinate> chunks
        ) throws IOException {
            byte[] header = new byte[REGION_HEADER_BYTES];
            try (InputStream input = Files.newInputStream(regionFile)) {
                int offset = 0;
                while (offset < header.length) {
                    int read = input.read(header, offset, header.length - offset);
                    if (read < 0) {
                        return;
                    }
                    offset += read;
                }
            }

            for (int index = 0; index < REGION_CHUNK_COUNT * REGION_CHUNK_COUNT; index++) {
                int headerOffset = index * 4;
                int sectorOffset = ((header[headerOffset] & 0xff) << 16)
                        | ((header[headerOffset + 1] & 0xff) << 8)
                        | (header[headerOffset + 2] & 0xff);
                int sectorCount = header[headerOffset + 3] & 0xff;
                if (sectorOffset == 0 || sectorCount == 0) {
                    continue;
                }

                int localX = index % REGION_CHUNK_COUNT;
                int localZ = index / REGION_CHUNK_COUNT;
                chunks.add(new ChunkCoordinate(
                        regionX * REGION_CHUNK_COUNT + localX,
                        regionZ * REGION_CHUNK_COUNT + localZ
                ));
            }
        }
    }

    private final class ExistingOreScanJob {
        private final World world;
        private final List<ChunkCoordinate> chunks;
        private final CommandSender sender;
        private final Iterator<ChunkCoordinate> iterator;
        private BukkitTask task;
        private int scannedChunks;
        private int replacedBlocks;

        private ExistingOreScanJob(World world, List<ChunkCoordinate> chunks, CommandSender sender) {
            this.world = Objects.requireNonNull(world);
            this.chunks = List.copyOf(chunks);
            this.sender = Objects.requireNonNull(sender);
            this.iterator = this.chunks.iterator();
        }

        private void start() {
            task = getServer().getScheduler().runTaskTimer(
                    SpsmcBuildNoOresPlugin.this,
                    this::tick,
                    1L,
                    1L
            );
        }

        private void tick() {
            try {
                for (int i = 0; i < CHUNKS_PER_TICK && iterator.hasNext(); i++) {
                    ChunkCoordinate coordinate = iterator.next();
                    boolean wasLoaded = world.isChunkLoaded(coordinate.x(), coordinate.z());
                    if (!world.isChunkGenerated(coordinate.x(), coordinate.z())) {
                        continue;
                    }

                    Chunk chunk = world.getChunkAt(coordinate.x(), coordinate.z(), false);
                    replacedBlocks += replaceOresInChunk(chunk);
                    scannedChunks++;

                    if (!wasLoaded) {
                        world.unloadChunk(coordinate.x(), coordinate.z(), true);
                    }
                }

                if (!iterator.hasNext()) {
                    task.cancel();
                    finishExistingOreScan(this);
                } else if (scannedChunks > 0 && scannedChunks % 100 == 0) {
                    sendMessage("既存buildワールドをスキャン中: " + scannedChunks + "/" + chunks.size() + "チャンク");
                }
            } catch (RuntimeException exception) {
                task.cancel();
                failExistingOreScan(this, exception);
            }
        }

        private void cancel() {
            if (task != null) {
                task.cancel();
            }
        }

        private int totalChunks() {
            return chunks.size();
        }

        private int scannedChunks() {
            return scannedChunks;
        }

        private int replacedBlocks() {
            return replacedBlocks;
        }

        private void sendMessage(String message) {
            sender.sendMessage("[SPSMCBuildNoOres] " + message);
        }
    }

    private static final class OreRemovalPopulator extends BlockPopulator {
        @Override
        public void populate(
                WorldInfo worldInfo,
                java.util.Random random,
                int chunkX,
                int chunkZ,
                LimitedRegion limitedRegion
        ) {
            int minX = chunkX << 4;
            int maxX = minX + 16;
            int minZ = chunkZ << 4;
            int maxZ = minZ + 16;

            for (int x = minX; x < maxX; x++) {
                for (int z = minZ; z < maxZ; z++) {
                    for (int y = worldInfo.getMinHeight(); y < worldInfo.getMaxHeight(); y++) {
                        Material material = limitedRegion.getType(x, y, z);
                        Material replacement = replacementFor(material);
                        if (replacement != null) {
                            limitedRegion.setType(x, y, z, replacement);
                        }
                    }
                }
            }
        }

    }
}
