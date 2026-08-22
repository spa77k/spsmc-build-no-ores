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
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final String EXISTING_CHUNKS_FILE = "existing-build-chunks.txt";
    private static final int REGION_HEADER_BYTES = 8192;
    private static final int REGION_CHUNK_COUNT = 32;
    private static final int CHUNKS_PER_TICK = 1;
    private static final int MAX_CHUNK_LOAD_ATTEMPTS = 5;
    private static final long AUTOMATIC_SCAN_DELAY_TICKS = 100L;
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
    private BukkitTask automaticScanTask;

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
            scheduleAutomaticScan(world);
        }
    }

    @Override
    public void onDisable() {
        if (automaticScanTask != null) {
            automaticScanTask.cancel();
            automaticScanTask = null;
        }
        if (existingOreScanJob != null) {
            existingOreScanJob.cancel();
            existingOreScanJob = null;
        }
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        // Multiverse-Core が後から build を作成する場合はこちらで登録します。
        registerPopulator(event.getWorld());
        scheduleAutomaticScan(event.getWorld());
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

    private void scheduleAutomaticScan(World world) {
        if (!TARGET_WORLD.equals(world.getName())
                || world.getEnvironment() != World.Environment.NORMAL
                || getConfig().getBoolean(SCAN_COMPLETED_PATH, false)
                || existingOreScanJob != null
                || automaticScanTask != null) {
            return;
        }

        automaticScanTask = getServer().getScheduler().runTaskLater(
                this,
                () -> {
                    automaticScanTask = null;
                    World buildWorld = Bukkit.getWorld(TARGET_WORLD);
                    if (buildWorld == null
                            || getConfig().getBoolean(SCAN_COMPLETED_PATH, false)
                            || existingOreScanJob != null) {
                        return;
                    }
                    getLogger().info("buildワールドの既存生成チャンクを自動スキャンします。");
                    beginExistingOreScan(buildWorld, Bukkit.getConsoleSender());
                },
                AUTOMATIC_SCAN_DELAY_TICKS
        );
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("buildnores")) {
            return false;
        }

        if (args.length != 1
                || (!args[0].equalsIgnoreCase("scan") && !args[0].equalsIgnoreCase("rescan"))) {
            sender.sendMessage("使い方: /buildnores scan または /buildnores rescan");
            return true;
        }

        boolean rescan = args[0].equalsIgnoreCase("rescan");

        if (!sender.hasPermission("spsmcbuildnores.scan")) {
            sender.sendMessage("このコマンドを実行する権限がありません。");
            return true;
        }

        if (existingOreScanJob != null) {
            sender.sendMessage("既存buildワールドの鉱石スキャンはすでに実行中です。");
            return true;
        }

        if (!rescan && getConfig().getBoolean(SCAN_COMPLETED_PATH, false)) {
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

        if (rescan) {
            getConfig().set(SCAN_COMPLETED_PATH, false);
            saveConfig();
        }

        beginExistingOreScan(buildWorld, sender);
        return true;
    }

    private boolean beginExistingOreScan(World buildWorld, CommandSender sender) {
        if (existingOreScanJob != null) {
            sender.sendMessage("既存buildワールドの鉱石スキャンはすでに実行中です。");
            return false;
        }

        if (!TARGET_WORLD.equals(buildWorld.getName()) || buildWorld.getEnvironment() != World.Environment.NORMAL) {
            sender.sendMessage("buildワールドが通常環境ではないため、スキャンを中止しました。");
            return false;
        }

        List<ChunkCoordinate> indexedChunks;
        try {
            indexedChunks = ExistingChunkIndex.read(buildWorld);
        } catch (IOException exception) {
            getLogger().log(java.util.logging.Level.SEVERE, "buildワールドの既存チャンク一覧を読み取れませんでした。", exception);
            sender.sendMessage("buildワールドのチャンク一覧を読み取れませんでした。ログを確認してください。");
            return false;
        }

        List<ChunkCoordinate> chunks = indexedChunks;
        long generatedChunks = chunks.stream()
                .filter(coordinate -> buildWorld.isChunkGenerated(coordinate.x(), coordinate.z()))
                .count();

        Path coordinateFile;
        try {
            coordinateFile = writeExistingChunkSnapshot(buildWorld, chunks);
        } catch (IOException exception) {
            getLogger().log(java.util.logging.Level.SEVERE, "既存buildワールドのチャンク座標を保存できませんでした。", exception);
            sender.sendMessage("既存buildワールドのチャンク座標を保存できませんでした。スキャンを開始しません。");
            return false;
        }

        existingOreScanJob = new ExistingOreScanJob(buildWorld, chunks, sender);
        sender.sendMessage("既存リージョンチャンク: " + chunks.size() + "件（生成完了: " + generatedChunks
                + "件、生成途中: " + (chunks.size() - generatedChunks) + "件）。座標を保存しました: " + coordinateFile);
        sender.sendMessage(ChunkBounds.describe(chunks));
        existingOreScanJob.start();
        sender.sendMessage("既存buildワールドの鉱石スキャンを開始しました。完了までサーバーを停止しないでください。");
        return true;
    }

    private void finishExistingOreScan(ExistingOreScanJob job) {
        if (existingOreScanJob != job) {
            return;
        }

        if (job.scannedChunks() != job.totalChunks()) {
            getConfig().set(SCAN_COMPLETED_PATH, false);
            saveConfig();
            existingOreScanJob = null;
            getLogger().severe("既存buildワールドの鉱石スキャンが未完了のまま終了しました。完了扱いにはしません。"
                    + "対象チャンク: " + job.totalChunks() + ", スキャン済み: " + job.scannedChunks());
            job.sendMessage("既存buildワールドの鉱石スキャンは未完了です。完了フラグは設定せず、再実行できます。");
            return;
        }

        ChunkBounds bounds = job.bounds();
        String boundsPath = "migration.existing-build-scan-bounds";
        if (bounds == null) {
            getConfig().set(boundsPath, null);
        } else {
            getConfig().set(boundsPath + ".min-chunk-x", bounds.minChunkX());
            getConfig().set(boundsPath + ".max-chunk-x", bounds.maxChunkX());
            getConfig().set(boundsPath + ".min-chunk-z", bounds.minChunkZ());
            getConfig().set(boundsPath + ".max-chunk-z", bounds.maxChunkZ());
            getConfig().set(boundsPath + ".min-block-x", bounds.minBlockX());
            getConfig().set(boundsPath + ".max-block-x", bounds.maxBlockX());
            getConfig().set(boundsPath + ".min-block-z", bounds.minBlockZ());
            getConfig().set(boundsPath + ".max-block-z", bounds.maxBlockZ());
        }
        getConfig().set("migration.existing-build-chunks-file", EXISTING_CHUNKS_FILE);
        getConfig().set(SCAN_COMPLETED_PATH, true);
        saveConfig();
        existingOreScanJob = null;
        job.sendMessage("既存buildワールドの鉱石スキャンが完了しました。対象チャンク: "
                + job.totalChunks() + ", スキャン済み: " + job.scannedChunks()
                + ", 置換ブロック: " + job.replacedBlocks() + "。"
                + ChunkBounds.describe(bounds) + "この処理は再実行されません。");
    }

    private void failExistingOreScan(ExistingOreScanJob job, Exception exception) {
        if (existingOreScanJob != job) {
            return;
        }

        getConfig().set(SCAN_COMPLETED_PATH, false);
        saveConfig();
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

    private static int countOresInChunk(Chunk chunk) {
        int remaining = 0;
        World world = chunk.getWorld();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    if (ORE_MATERIALS.contains(chunk.getBlock(x, y, z).getType())) {
                        remaining++;
                    }
                }
            }
        }
        return remaining;
    }

    private static Material replacementFor(Material material) {
        if (!ORE_MATERIALS.contains(material)) {
            return null;
        }
        return material.name().startsWith("DEEPSLATE_") ? Material.DEEPSLATE : Material.STONE;
    }

    private Path writeExistingChunkSnapshot(World world, List<ChunkCoordinate> chunks) throws IOException {
        Files.createDirectories(getDataFolder().toPath());

        List<ChunkCoordinate> sortedChunks = new ArrayList<>(chunks);
        sortedChunks.sort(Comparator
                .comparingInt(ChunkCoordinate::x)
                .thenComparingInt(ChunkCoordinate::z));

        Path snapshot = getDataFolder().toPath().resolve(EXISTING_CHUNKS_FILE);
        Path temporary = snapshot.resolveSibling(EXISTING_CHUNKS_FILE + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(
                temporary,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            writer.write("# SPSMC Build No Ores existing generated chunk snapshot");
            writer.newLine();
            writer.write("# world=" + world.getName());
            writer.newLine();
            writer.write("# format=chunk_x,chunk_z");
            writer.newLine();
            for (ChunkCoordinate chunk : sortedChunks) {
                writer.write(Integer.toString(chunk.x()));
                writer.write(',');
                writer.write(Integer.toString(chunk.z()));
                writer.newLine();
            }
        }

        try {
            return Files.move(
                    temporary,
                    snapshot,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            return Files.move(temporary, snapshot, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record ChunkCoordinate(int x, int z) {
    }

    private record ChunkBounds(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        private static ChunkBounds from(List<ChunkCoordinate> chunks) {
            if (chunks.isEmpty()) {
                return null;
            }

            int minChunkX = Integer.MAX_VALUE;
            int maxChunkX = Integer.MIN_VALUE;
            int minChunkZ = Integer.MAX_VALUE;
            int maxChunkZ = Integer.MIN_VALUE;

            for (ChunkCoordinate chunk : chunks) {
                minChunkX = Math.min(minChunkX, chunk.x());
                maxChunkX = Math.max(maxChunkX, chunk.x());
                minChunkZ = Math.min(minChunkZ, chunk.z());
                maxChunkZ = Math.max(maxChunkZ, chunk.z());
            }
            return new ChunkBounds(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        }

        private int minBlockX() {
            return minChunkX * 16;
        }

        private int maxBlockX() {
            return maxChunkX * 16 + 15;
        }

        private int minBlockZ() {
            return minChunkZ * 16;
        }

        private int maxBlockZ() {
            return maxChunkZ * 16 + 15;
        }

        private static String describe(List<ChunkCoordinate> chunks) {
            return describe(from(chunks));
        }

        private static String describe(ChunkBounds bounds) {
            if (bounds == null) {
                return "生成済みチャンクはありません。";
            }
            return "チャンク範囲 X=" + bounds.minChunkX + ".." + bounds.maxChunkX
                    + ", Z=" + bounds.minChunkZ + ".." + bounds.maxChunkZ
                    + "（ブロック座標 X=" + bounds.minBlockX() + ".." + bounds.maxBlockX()
                    + ", Z=" + bounds.minBlockZ() + ".." + bounds.maxBlockZ() + "）。";
        }
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
        private final ChunkBounds bounds;
        private final CommandSender sender;
        private final ArrayDeque<ChunkCoordinate> pendingChunks;
        private final Map<ChunkCoordinate, Integer> loadAttempts = new HashMap<>();
        private BukkitTask task;
        private int scannedChunks;
        private int replacedBlocks;

        private ExistingOreScanJob(World world, List<ChunkCoordinate> chunks, CommandSender sender) {
            this.world = Objects.requireNonNull(world);
            this.chunks = List.copyOf(chunks);
            this.bounds = ChunkBounds.from(this.chunks);
            this.sender = Objects.requireNonNull(sender);
            this.pendingChunks = new ArrayDeque<>(this.chunks);
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
                for (int i = 0; i < CHUNKS_PER_TICK && !pendingChunks.isEmpty(); i++) {
                    ChunkCoordinate coordinate = pendingChunks.removeFirst();
                    int attempts = loadAttempts.merge(coordinate, 1, Integer::sum);
                    boolean wasLoaded = world.isChunkLoaded(coordinate.x(), coordinate.z());
                    boolean loaded = world.loadChunk(coordinate.x(), coordinate.z(), true);
                    if (!loaded || !world.isChunkGenerated(coordinate.x(), coordinate.z())) {
                        if (attempts >= MAX_CHUNK_LOAD_ATTEMPTS) {
                            task.cancel();
                            failExistingOreScan(this, new IllegalStateException(
                                    "既存チャンクを生成完了状態まで読み込めませんでした: " + coordinate
                                            + "（試行" + attempts + "回）"));
                            return;
                        }
                        pendingChunks.addLast(coordinate);
                        continue;
                    }

                    Chunk chunk = world.getChunkAt(coordinate.x(), coordinate.z(), false);
                    int replacedInChunk = replaceOresInChunk(chunk);
                    int remainingOres = countOresInChunk(chunk);
                    if (remainingOres != 0) {
                        task.cancel();
                        failExistingOreScan(this, new IllegalStateException(
                                "鉱石置換後も鉱石が残っています: " + coordinate
                                        + "（残存" + remainingOres + "ブロック）"));
                        return;
                    }

                    replacedBlocks += replacedInChunk;
                    scannedChunks++;
                    loadAttempts.remove(coordinate);

                    if (!wasLoaded) {
                        world.unloadChunk(coordinate.x(), coordinate.z(), true);
                    }
                }

                if (pendingChunks.isEmpty()) {
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

        private ChunkBounds bounds() {
            return bounds;
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
