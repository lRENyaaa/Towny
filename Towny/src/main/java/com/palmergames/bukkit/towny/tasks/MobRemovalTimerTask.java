package com.palmergames.bukkit.towny.tasks;

import com.palmergames.bukkit.config.ConfigNodes;
import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.event.MobRemovalEvent;
import com.palmergames.bukkit.towny.hooks.PluginIntegrations;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.utils.EntityTypeUtil;
import com.palmergames.bukkit.util.BukkitTools;

import com.palmergames.util.JavaUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MobRemovalTimerTask extends TownyTimerTask {

	public static List<Class<?>> classesOfWorldMobsToRemove = new ArrayList<>();
	public static List<Class<?>> classesOfWildernessMobsToRemove = new ArrayList<>();
	public static List<Class<?>> classesOfTownMobsToRemove = new ArrayList<>();
	private static final Set<String> ignoredSpawnReasons = new HashSet<>();
	private boolean isRemovingKillerBunny;
	
	// https://jd.papermc.io/paper/1.20/org/bukkit/entity/Entity.html#getEntitySpawnReason()
	private static final MethodHandle GET_SPAWN_REASON = JavaUtil.getMethodHandle(Entity.class, "getEntitySpawnReason");

	public MobRemovalTimerTask(Towny plugin) {
		super(plugin);

		populateFields();
		TownySettings.addReloadListener(NamespacedKey.fromString("towny:mob-removal-task"), config -> this.populateFields());
	}

	public static boolean isRemovingWorldEntity(LivingEntity livingEntity) {
		return EntityTypeUtil.isInstanceOfAny(classesOfWorldMobsToRemove, livingEntity);
	}
	
	public static boolean isRemovingWildernessEntity(LivingEntity livingEntity) {
		return  EntityTypeUtil.isInstanceOfAny(classesOfWildernessMobsToRemove, livingEntity);
	}

	public static boolean isRemovingTownEntity(LivingEntity livingEntity) {
		return EntityTypeUtil.isInstanceOfAny(classesOfTownMobsToRemove, livingEntity);
	}
	
	public static boolean isSpawnReasonIgnored(@NotNull Entity entity) {
		return isSpawnReasonIgnored(entity, null);
	}

	public static boolean isSpawnReasonIgnored(@NotNull Entity entity, @Nullable CreatureSpawnEvent.SpawnReason spawnReason) {
		if (spawnReason != null && ignoredSpawnReasons.contains(spawnReason.name()))
			return true;

		if (GET_SPAWN_REASON == null || ignoredSpawnReasons.isEmpty())
			return false;

		try {
			final Enum<?> reason = (Enum<?>) GET_SPAWN_REASON.invoke(entity);

			return ignoredSpawnReasons.contains(reason.name());
		} catch (Throwable throwable) {
			return false;
		}
	}

	@Override
	public void run() {
		for (final World world : Bukkit.getWorlds()) {
			// Filter worlds not using towny.
			final TownyWorld townyWorld = TownyAPI.getInstance().getTownyWorld(world);
			if (townyWorld == null || !townyWorld.isUsingTowny())
				continue;

			// Filter worlds that will always pass all checks in a world, regardless of possible conditions.
			if (townyWorld.isForceTownMobs() && townyWorld.hasWorldMobs())
				continue;

			final List<LivingEntity> entities = world.getLivingEntities();
			if (entities.isEmpty())
				continue;
			
			for (final LivingEntity entity : entities) {
				// Check if entity is a player or Citizens NPC
				if (entity instanceof Player || PluginIntegrations.getInstance().isNPC(entity))
					continue;

				// Handles entities Globally.
				if (!townyWorld.hasWorldMobs() && isRemovingWorldEntity(entity)) {
					removeEntity(entity);
					continue;
				}

				final Runnable runnable = () -> {
					final Location livingEntityLoc = entity.getLocation();
					final TownBlock townBlock = TownyAPI.getInstance().getTownBlock(livingEntityLoc);
						
					// Handles entities in the wilderness.
					if (townBlock == null) {
						if (townyWorld.hasWildernessMobs() || !isRemovingWildernessEntity(entity))
							return;
					} else {
						// The entity is inside of a town.
	
						// Check if mobs are always allowed inside towns in this world, if the townblock allows it, or if the town has mobs forced on.
						if (townyWorld.isForceTownMobs() || townBlock.getPermissions().mobs || townBlock.getTownOrNull().isAdminEnabledMobs())
							return;
	
						// Check that Towny is removing this type of entity inside towns.
						if (!isRemovingTownEntity(entity))
							return;
					}
	
					// Check if this is an EliteMob before we do any skipping-removal-of-named-mobs.
					if (PluginIntegrations.getInstance().checkHostileEliteMobs(entity)) {
						removeEntity(entity);
						return;
					}

					// Special check if it's a rabbit, for the Killer Bunny variant.
					if (entity instanceof Rabbit rabbit && isRemovingKillerBunny && rabbit.getRabbitType() == Rabbit.Type.THE_KILLER_BUNNY) {
						removeEntity(entity);
						return;
					}

					if (TownySettings.isSkippingRemovalOfNamedMobs() && entity.getCustomName() != null)
						return;
					
					// Don't remove if the entity's spawn reason is considered ignored by the config
					if (isSpawnReasonIgnored(entity))
						return;

					removeEntity(entity);
				};
				
				if (plugin.isFolia())
					plugin.getScheduler().run(entity, runnable);
				else
					runnable.run();
			}
		}
	}
	
	private void removeEntity(@NotNull Entity entity) {
		if (MobRemovalEvent.getHandlerList().getRegisteredListeners().length > 0 && BukkitTools.isEventCancelled(new MobRemovalEvent(entity)))
			return;

		if (!plugin.getScheduler().isEntityThread(entity))
			plugin.getScheduler().run(entity, entity::remove);
		else
			entity.remove();
	}
	
	private void populateFields() {
		classesOfWorldMobsToRemove = EntityTypeUtil.parseLivingEntityClassNames(TownySettings.getWorldMobRemovalEntities(), "WorldMob: ");
		classesOfWildernessMobsToRemove = EntityTypeUtil.parseLivingEntityClassNames(TownySettings.getWildernessMobRemovalEntities(),"WildernessMob: ");
		classesOfTownMobsToRemove = EntityTypeUtil.parseLivingEntityClassNames(TownySettings.getTownMobRemovalEntities(), "TownMob: ");
		isRemovingKillerBunny = TownySettings.isRemovingKillerBunny();
		
		ignoredSpawnReasons.clear();
		for (final String cause : TownySettings.getStrArr(ConfigNodes.PROT_MOB_REMOVE_IGNORED_SPAWN_CAUSES))
			ignoredSpawnReasons.add(cause.toUpperCase(Locale.ROOT));
	}
}
