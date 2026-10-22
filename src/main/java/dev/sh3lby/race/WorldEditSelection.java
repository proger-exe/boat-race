package dev.sh3lby.race;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.entity.Player;

/**
 * Reads the //wand selection through the WorldEdit singleton, so FastAsyncWorldEdit works too.
 * Kept in its own class: these classes load only after {@link RaceSetup} saw WorldEdit installed.
 */
final class WorldEditSelection {

    /** {minX, minY, minZ, maxX, maxY, maxZ} of the selection, or null when nothing is selected here. */
    static int[] corners(Player player) {
        try {
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
            Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            return new int[]{min.getBlockX(), min.getBlockY(), min.getBlockZ(),
                    max.getBlockX(), max.getBlockY(), max.getBlockZ()};
        } catch (IncompleteRegionException | NullPointerException exception) {
            return null;
        }
    }
}
