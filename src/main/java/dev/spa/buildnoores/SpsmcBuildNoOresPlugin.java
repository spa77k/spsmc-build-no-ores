package dev.spa.buildnoores;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

/**
 * 建築ワールドだけから自然生成鉱石を除去するPaperプラグインです。
 */
public final class SpsmcBuildNoOresPlugin extends JavaPlugin implements Listener {
    private static final String TARGET_WORLD = "build";

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

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        // build がプラグイン有効化より先に読み込まれていた場合にも登録します。
        for (World world : Bukkit.getWorlds()) {
            registerPopulator(world);
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

        private static Material replacementFor(Material material) {
            if (!ORE_MATERIALS.contains(material)) {
                return null;
            }
            return material.name().startsWith("DEEPSLATE_") ? Material.DEEPSLATE : Material.STONE;
        }
    }
}
