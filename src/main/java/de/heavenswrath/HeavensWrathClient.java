package de.heavenswrath;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client-Mod: Tötest du einen Spieler im Nahkampf, kracht eine RIESIGE Version deiner
 * Waffe (oder eines per /killeffect gewählten Items) vom Himmel auf den Block unter ihm
 * und bleibt kurz im Boden stecken. Alles passiert nur lokal bei dir (kein Server-Mod nötig).
 *
 * Einstellungen und Chat-Menü: {@link HeavensWrathConfig} und {@link HeavensWrathBefehl}.
 */
public class HeavensWrathClient implements ClientModInitializer {

	/** Wie hoch über dem Opfer die Waffe startet (in Blöcken). */
	private static final double FALL_HOEHE = 40.0;
	/** Größe der Waffe (1 = normales Item, 6 = sechs Blöcke groß). */
	private static final float SKALIERUNG = 6.0f;
	/** Fallbeschleunigung pro Tick (größer = schneller). */
	private static final double GRAVITATION = 0.10;
	/** So lange (in Ticks) bleibt die Waffe nach dem Einschlag im Boden stecken. */
	private static final int STECKEN_TICKS = 50;
	/** So lange (in Ticks) nach dem letzten Treffer zählt ein Tod noch als "mein Kill". */
	private static final int KILL_FENSTER = 40;
	/**
	 * false = die Kopie hat den Skin des getöteten Spielers (Standard).
	 * true  = die Kopie hat DEINEN eigenen Skin.
	 */
	private static final boolean KOPIE_MIT_EIGENEM_SKIN = false;
	/** Sicherheits-Timeout, falls die Waffe nie aufschlägt. */
	private static final int MAX_FALL_TICKS = 200;

	/**
	 * Wie tief die Spitze im Boden steckt. Die Waffe wird um 45° gedreht, die Spitze
	 * liegt dann ca. 0,7 * Größe unter der Mitte.
	 */
	private static final double MITTE_ÜBER_BODEN = 0.7 * SKALIERUNG - 0.4;

	private static class Fallend {
		final Entity entity;
		final boolean riesig;
		final double x;
		final double z;
		final double bodenY;
		double y;
		double vy = 0.0;
		int alter = 0;
		int steckt = -1; // -1 = fällt noch
		/** Stehende Kopie des Opfers, bleibt bis die Waffe weg ist. */
		Entity koerper = null;
		/** Stufe von Verbrennung (Fire Aspect) auf der Waffe, 0 = keine. */
		int feuer = 0;

		// ---- Angriffs-Modi (Ziege, Lama, Warden, Eisengolem); art == null = normaler Heaven's Wrath ----
		String art = null;
		double dirX, dirZ; // Blickrichtung des Tieres zum Opfer
		double tx, tz; // aktuelle Position des Tieres
		int phase = 0; // 0 = Anlauf/Vorbereitung, 1 = Treffer erfolgt
		int zaehler = 0;
		int nachTreffer = 0;
		boolean flug = false; // Körper fliegt gerade
		boolean landet = false; // Körper landet am Boden (sonst: verpufft am Ende)
		double kvx, kvy, kvz, grav;
		int flugTicks = 0;
		int maxFlug = 40;
		// ---- Assist-Spieler (Mace / Schwert) ----
		double fy, fvy; // Höhe und Fallgeschwindigkeit des Fake-Spielers
		Entity extra = null; // z. B. das riesige Schwert im Körper
		ItemStack stack = ItemStack.EMPTY;

		Fallend(Entity entity, boolean riesig, double x, double y, double z, double bodenY) {
			this.entity = entity;
			this.riesig = riesig;
			this.x = x;
			this.y = y;
			this.z = z;
			this.bodenY = bodenY;
		}
	}

	private final List<Fallend> fallende = new ArrayList<>();
	private static int nextClientId = -2_000_000;

	private long tick = 0;
	private int letztesZielId = -1;
	private ItemStack letzteWaffe = ItemStack.EMPTY;
	private long letzterTreffer = -1000;

	@Override
	public void onInitializeClient() {
		HeavensWrathConfig.laden();
		HeavensWrathTaste.registrieren();
		ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess) -> HeavensWrathBefehl.registrieren(dispatcher));

		// Letzten Treffer des lokalen Spielers merken (Waffe = Item in der Hand).
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			Minecraft mc = Minecraft.getInstance();
			if (world.isClientSide() && player == mc.player && entity instanceof Player) {
				letztesZielId = entity.getId();
				letzteWaffe = player.getMainHandItem().copy();
				letzterTreffer = tick;
			}
			return InteractionResult.PASS;
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		HeavensWrathScreen.pruefeOeffnen(mc);
		HeavensWrathTaste.tick(mc);
		tick++;
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			for (Fallend f : fallende) {
				entferne(f);
			}
			fallende.clear();
			letztesZielId = -1;
			return;
		}

		pruefeKill(mc, level);
		aktualisiereFallende(level);
	}

	private void pruefeKill(Minecraft mc, ClientLevel level) {
		if (letztesZielId == -1) {
			return;
		}
		if (tick - letzterTreffer > KILL_FENSTER) {
			letztesZielId = -1;
			return;
		}

		Entity e = level.getEntity(letztesZielId);
		if (e instanceof Player opfer && opfer != mc.player && opfer.isDeadOrDying()) {
			letztesZielId = -1;
			// Battle Pass: jeder Kill zählt, auch wenn der Effekt gerade aus ist.
			HeavensWrathPass.kill(mc);
			if (!HeavensWrathConfig.aktiv) {
				return;
			}
			String modus = HeavensWrathPass.freigeschaltet(HeavensWrathConfig.modus) ? HeavensWrathConfig.modus : "waffe";
			double bodenY = findeBoden(level, opfer);
			switch (modus) {
				case "ziege", "lama", "warden", "golem", "blitz", "engel" -> {
					starteAngriff(level, opfer, bodenY, modus);
					return;
				}
				case "assist" -> {
					// Assist gibt es nur mit Mace oder Schwert, sonst fällt die normale Waffe.
					if (letzteWaffe.is(Items.MACE) || letzteWaffe.is(ItemTags.SWORDS)) {
						starteAngriff(level, opfer, bodenY, "assist");
						return;
					}
				}
				default -> {
				}
			}
			boolean meteor = "meteor".equals(modus);
			// Welches Item fällt? Standard: die Waffe, mit der getötet wurde. Meteor: ein Magmablock.
			ItemStack anzeige = meteor ? new ItemStack(Items.MAGMA_BLOCK) : HeavensWrathConfig.waehle(letzteWaffe);
			if (anzeige == null || anzeige.isEmpty()) {
				return;
			}
			// Waffe und Körper immer auf dem Block UNTER dem Spieler.
			starteAufprall(level, opfer.getX(), bodenY, opfer.getZ(), anzeige);
			if (!fallende.isEmpty()) {
				Fallend neu = fallende.get(fallende.size() - 1);
				neu.koerper = erzeugeKoerper(level, opfer, bodenY);
				// Feuer richtet sich nach der echten Waffe (Verbrennung); der Meteor brennt immer stark.
				neu.feuer = meteor ? 2 : feuerStufe(letzteWaffe);
			}
		}
	}

	/** Der Block unter dem Spieler: Oberkante des ersten festen Blocks unterhalb seiner Füße. */
	private static double findeBoden(ClientLevel level, Player opfer) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(opfer.getBlockX(), opfer.getBlockY(), opfer.getBlockZ());
		int min = level.getMinY();
		while (pos.getY() >= min) {
			BlockState state = level.getBlockState(pos);
			var form = state.getCollisionShape(level, pos);
			if (!form.isEmpty()) {
				return pos.getY() + form.max(Direction.Axis.Y);
			}
			pos.move(Direction.DOWN);
		}
		return opfer.getY(); // z. B. über dem Void: dort bleiben, wo er gestorben ist
	}

	private void starteAufprall(ClientLevel level, double x, double bodenY, double z, ItemStack waffe) {
		double startY = Math.min(bodenY + MITTE_ÜBER_BODEN + FALL_HOEHE, level.getMaxY() - 1);

		// 1. Versuch: riesige, gedrehte Waffe (Item-Display-Entity)
		Entity display = erzeugeRiesigesDisplay(level, waffe);
		if (display != null) {
			display.setId(nextClientId--);
			display.setPos(x, startY, z);
			level.addEntity(display);
			fallende.add(new Fallend(display, true, x, startY, z, bodenY));
			return;
		}

		// 2. Fallback: normales, herabfallendes Item
		ItemEntity item = new ItemEntity(level, x, startY, z, waffe.copy());
		item.setId(nextClientId--);
		item.setNeverPickUp();
		item.setUnlimitedLifetime();
		item.setDeltaMovement(0.0, -0.8, 0.0);
		level.addEntity(item);
		fallende.add(new Fallend(item, false, x, startY, z, bodenY));
	}

	/** Riesige Waffe mit der Spitze nach unten (Waffen-Sprites zeigen schräg nach oben, 135° drehen). */
	private Entity erzeugeRiesigesDisplay(ClientLevel level, ItemStack waffe) {
		Transformation trafo = new Transformation(
				new Vector3f(0, 0, 0),
				(waffe.getItem() instanceof BlockItem)
						? new Quaternionf() // Blöcke (z. B. Amboss) nicht kippen
						: new Quaternionf().rotateZ((float) Math.toRadians(135.0)),
				new Vector3f(SKALIERUNG, SKALIERUNG, SKALIERUNG),
				new Quaternionf());
		return erzeugeDisplay(level, waffe, trafo, Display.BillboardConstraints.CENTER);
	}

	/**
	 * Baut ein ItemDisplay mit gegebener Größe/Drehung. Die Setter sind nicht öffentlich, daher
	 * per Reflection (26.x ist unobfuskiert, die Namen sind also in Entwicklung und im Spiel gleich).
	 */
	private Display.ItemDisplay erzeugeDisplay(ClientLevel level, ItemStack stack, Transformation trafo,
			Display.BillboardConstraints billboard) {
		try {
			Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
			// <REFLEKTION>
			Method setItem = Display.ItemDisplay.class.getDeclaredMethod("setItemStack", ItemStack.class);
			setItem.setAccessible(true);
			setItem.invoke(display, stack.copy());

			Method setContext = Display.ItemDisplay.class.getDeclaredMethod("setItemTransform", ItemDisplayContext.class);
			setContext.setAccessible(true);
			setContext.invoke(display, ItemDisplayContext.GUI);

			Method setBillboard = Display.class.getDeclaredMethod("setBillboardConstraints",
					Display.BillboardConstraints.class);
			setBillboard.setAccessible(true);
			setBillboard.invoke(display, billboard);

			Method setTrafo = Display.class.getDeclaredMethod("setTransformation", Transformation.class);
			setTrafo.setAccessible(true);
			setTrafo.invoke(display, trafo);
			// </REFLEKTION>
			return display;
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Display nicht möglich: " + t);
			return null;
		}
	}

	/**
	 * Das echte Opfer kippt um und wird nach ca. 1 Sekunde vom Server entfernt – das
	 * kann der Client nicht verhindern. Darum: Original unsichtbar machen und eine
	 * stehende, lokale Kopie (gleicher Skin, gleiche Rüstung) auf den Boden darunter setzen.
	 */
	private Entity erzeugeKoerper(ClientLevel level, Player opfer, double bodenY) {
		try {
			// Skin fest übernehmen: Die Kopie hat eine eigene UUID, damit würde der
			// Skin-Lookup sonst ins Leere laufen (-> Steve/Alex).
			Player skinQuelle = KOPIE_MIT_EIGENEM_SKIN && Minecraft.getInstance().player != null
					? Minecraft.getInstance().player
					: opfer;
			PlayerSkin skin = skinQuelle instanceof AbstractClientPlayer acp ? acp.getSkin() : null;

			RemotePlayer kopie = new RemotePlayer(level, opfer.getGameProfile()) {
				@Override
				public PlayerSkin getSkin() {
					return skin != null ? skin : super.getSkin();
				}
			};
			kopie.setUUID(UUID.randomUUID()); // sonst Konflikt mit dem Original
			kopie.setId(nextClientId--);
			kopie.setPos(opfer.getX(), bodenY, opfer.getZ());
			kopie.setNoGravity(true);
			kopie.setYRot(opfer.getYRot());
			kopie.yRotO = opfer.getYRot();
			kopie.setXRot(opfer.getXRot());
			kopie.yBodyRot = opfer.yBodyRot;
			kopie.yBodyRotO = opfer.yBodyRot;
			kopie.yHeadRot = opfer.yHeadRot;
			kopie.yHeadRotO = opfer.yHeadRot;
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				kopie.setItemSlot(slot, opfer.getItemBySlot(slot).copy());
			}
			// Zweite Skin-Ebene (Jacke, Ärmel, Hose, Hut, Umhang) vom Original übernehmen.
			try {
				var werte = opfer.getEntityData().getNonDefaultValues();
				if (werte != null) {
					kopie.getEntityData().assignValues(werte);
				}
			} catch (Throwable t) {
				System.err.println("[Heaven's Wrath] Skin-Ebenen nicht übernommen: " + t);
			}
			// Falls dabei Tod/Unsichtbarkeit/Pose mitkopiert wurde: zurücksetzen.
			kopie.setInvisible(false);
			kopie.setPose(Pose.STANDING);
			kopie.setHealth(kopie.getMaxHealth());
			level.addEntity(kopie);

			opfer.setInvisible(true); // Original (mit Todesanimation) ausblenden
			return kopie;
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Stehender Körper nicht möglich: " + t);
			return null;
		}
	}

	private void entferne(Fallend f) {
		f.entity.discard();
		if (f.koerper != null) {
			f.koerper.discard();
		}
		if (f.extra != null) {
			f.extra.discard();
		}
	}

	private void aktualisiereFallende(ClientLevel level) {
		Iterator<Fallend> it = fallende.iterator();
		while (it.hasNext()) {
			Fallend f = it.next();
			f.alter++;

			if (f.entity.isRemoved()) {
				entferne(f);
				it.remove();
				continue;
			}

			if (f.art != null) {
				if (aktualisiereAngriff(level, f)) {
					entferne(f);
					it.remove();
				}
				continue;
			}

			if (f.riesig) {
				if (f.steckt < 0) {
					f.vy -= GRAVITATION;
					f.y += f.vy;
					if (f.feuer > 0) {
						feuerFlug(level, f);
					}
					double ziel = f.bodenY + MITTE_ÜBER_BODEN;
					if (f.y <= ziel || f.alter > MAX_FALL_TICKS) {
						f.y = ziel;
						f.steckt = 0;
						einschlag(level, f.x, f.bodenY, f.z);
						koerperEffekt(level, f);
						if (f.feuer > 0) {
							feuerEinschlag(level, f, f.x, f.bodenY, f.z);
						}
					}
					f.entity.setPos(f.x, f.y, f.z);
				} else {
					f.steckt++;
					if (f.feuer > 0) {
						feuerAmBoden(level, f);
					}
					if (f.steckt >= STECKEN_TICKS) {
						entferne(f);
						it.remove();
					}
				}
			} else {
				boolean aufgeschlagen = f.entity.onGround()
						|| f.entity.getY() <= f.bodenY + 0.3
						|| f.alter > MAX_FALL_TICKS;
				if (aufgeschlagen) {
					einschlag(level, f.entity.getX(), f.entity.getY(), f.entity.getZ());
					koerperEffekt(level, f);
					if (f.feuer > 0) {
						feuerEinschlag(level, f, f.entity.getX(), f.entity.getY(), f.entity.getZ());
					}
					entferne(f);
					it.remove();
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Angriffs-Modi: Ziege, Lama, Warden, Eisengolem (/killeffect modus ...)
	// ------------------------------------------------------------------

	private static final double ZIEGE_TEMPO = 0.9;
	/** So lange (in Ticks) bleibt das Tier nach dem Treffer noch stehen. */
	private static final int NACHLAUF_TICKS = 80;

	private void starteAngriff(ClientLevel level, Player opfer, double bodenY, String art) {
		if ("assist".equals(art)) {
			starteAssist(level, opfer, bodenY);
			return;
		}
		EntityType<?> typ = switch (art) {
			case "ziege" -> EntityType.GOAT;
			case "lama" -> EntityType.LLAMA;
			case "warden" -> EntityType.WARDEN;
			case "golem" -> EntityType.IRON_GOLEM;
			default -> EntityType.ITEM_DISPLAY; // Blitz, Engel: nur ein unsichtbarer Platzhalter
		};
		double abstand = switch (art) {
			case "ziege" -> 14.0;
			case "lama" -> 6.0;
			case "warden" -> 12.0;
			case "golem" -> 2.4;
			default -> 0.0;
		};
		try {
			Entity tier = typ.create(level, EntitySpawnReason.COMMAND);
			if (tier == null) {
				return;
			}
			double winkel = Math.random() * Math.PI * 2;
			double dirX = -Math.cos(winkel);
			double dirZ = -Math.sin(winkel);
			double x = opfer.getX();
			double z = opfer.getZ();
			double sx = x - dirX * abstand;
			double sz = z - dirZ * abstand;
			float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));

			tier.setId(nextClientId--);
			tier.setPos(sx, bodenY, sz);
			tier.setYRot(yaw);
			tier.yRotO = yaw;
			if (tier instanceof LivingEntity le) {
				le.yBodyRot = yaw;
				le.yBodyRotO = yaw;
				le.yHeadRot = yaw;
				le.yHeadRotO = yaw;
			}
			level.addEntity(tier);

			Fallend f = new Fallend(tier, false, x, bodenY, z, bodenY);
			f.art = art;
			f.dirX = dirX;
			f.dirZ = dirZ;
			f.tx = sx;
			f.tz = sz;
			f.koerper = erzeugeKoerper(level, opfer, bodenY);
			fallende.add(f);
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Angriff nicht möglich: " + t);
		}
	}

	// ------------------------------------------------------------------
	// Blitz
	// ------------------------------------------------------------------

	/** Ein echter Blitz (nur optisch, nur bei dir). Der Donner kommt vom Blitz selbst. */
	private static void blitz(ClientLevel level, double x, double y, double z) {
		try {
			Entity bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.COMMAND);
			if (bolt != null) {
				bolt.setPos(x, y, z);
				bolt.setId(nextClientId--);
				level.addEntity(bolt);
			}
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Blitz nicht möglich: " + t);
		}
		for (int i = 0; i < 20; i++) {
			level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y + 0.5, z,
					(Math.random() - 0.5) * 0.8, Math.random() * 0.6, (Math.random() - 0.5) * 0.8);
		}
	}

	// ------------------------------------------------------------------
	// Assist: ein Fake-Spieler (Skin deiner Wahl) hilft beim Kill
	//   Mace   -> kommt aus dem Himmel und schlägt mit dem Mace zu
	//   Schwert -> rennt heran und rammt das Schwert durch ihn durch
	// ------------------------------------------------------------------

	private void starteAssist(ClientLevel level, Player opfer, double bodenY) {
		boolean mace = letzteWaffe.is(Items.MACE);
		double winkel = Math.random() * Math.PI * 2;
		double dirX = -Math.cos(winkel);
		double dirZ = -Math.sin(winkel);
		double x = opfer.getX();
		double z = opfer.getZ();
		double startAbstand = mace ? 1.4 : 9.0;
		double sx = x - dirX * startAbstand;
		double sz = z - dirZ * startAbstand;
		double sy = mace ? Math.min(bodenY + 38.0, level.getMaxY() - 1) : bodenY;
		float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
		ItemStack hand = mace ? new ItemStack(Items.MACE) : letzteWaffe.copy();

		Entity fake = erzeugeAssistSpieler(level, sx, sy, sz, yaw, hand);
		if (fake == null) {
			return;
		}
		Fallend f = new Fallend(fake, false, x, bodenY, z, bodenY);
		f.art = mace ? "assist_mace" : "assist_sword";
		f.dirX = dirX;
		f.dirZ = dirZ;
		f.tx = sx;
		f.tz = sz;
		f.fy = sy;
		f.stack = hand;
		f.koerper = erzeugeKoerper(level, opfer, bodenY);
		fallende.add(f);
	}

	/** Fake-Spieler mit dem Skin aus der Config (leer = dein eigener Skin). */
	private Entity erzeugeAssistSpieler(ClientLevel level, double x, double y, double z, float yaw, ItemStack hand) {
		try {
			Minecraft mc = Minecraft.getInstance();
			PlayerInfo info = null;
			String wunsch = HeavensWrathConfig.assistSkin;
			if (!wunsch.isEmpty() && mc.getConnection() != null) {
				for (PlayerInfo i : mc.getConnection().getOnlinePlayers()) {
					if (wunsch.equalsIgnoreCase(HeavensWrathConfig.profilName(i.getProfile()))) {
						info = i;
						break;
					}
				}
			}
			var profil = info != null ? info.getProfile() : mc.player.getGameProfile();
			PlayerSkin skin = info != null ? info.getSkin() : mc.player.getSkin();

			RemotePlayer fake = new RemotePlayer(level, profil) {
				@Override
				public PlayerSkin getSkin() {
					return skin != null ? skin : super.getSkin();
				}
			};
			fake.setUUID(UUID.randomUUID());
			fake.setId(nextClientId--);
			fake.setPos(x, y, z);
			fake.setNoGravity(true);
			fake.setYRot(yaw);
			fake.yRotO = yaw;
			fake.yBodyRot = yaw;
			fake.yBodyRotO = yaw;
			fake.yHeadRot = yaw;
			fake.yHeadRotO = yaw;
			fake.setItemSlot(EquipmentSlot.MAINHAND, hand.copy());
			fake.setHealth(fake.getMaxHealth());
			level.addEntity(fake);
			return fake;
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Assist-Spieler nicht möglich: " + t);
			return null;
		}
	}

	/** Gibt true zurück, wenn der Assist komplett vorbei ist. */
	private boolean aktualisiereAssist(ClientLevel level, Fallend f) {
		Entity fake = f.entity;
		if ("assist_mace".equals(f.art)) {
			if (f.phase == 0) { // Sturzflug aus dem Himmel
				f.fvy -= 0.14;
				f.fy += f.fvy;
				level.addParticle(ParticleTypes.CLOUD, f.tx, f.fy + 1.0, f.tz, 0.0, 0.05, 0.0);
				if (f.fy <= f.bodenY) {
					f.fy = f.bodenY;
					f.phase = 1;
					f.nachTreffer = 0;
					maceSchlag(level, f, true);
				}
				fake.setPos(f.tx, f.fy, f.tz);
				return false;
			}
			f.nachTreffer++;
			fake.setPos(f.tx, f.bodenY, f.tz);
			if (f.nachTreffer == 14 || f.nachTreffer == 28) {
				maceSchlag(level, f, false);
			}
			if (f.nachTreffer == 34) {
				HeavensWrathConfig.spieleSound(level, f.x, f.bodenY, f.z);
				koerperEffekt(level, f);
			}
			return f.nachTreffer > 60;
		}

		// Schwert: heranrennen, dann durchrammen
		if (f.phase == 0) {
			f.tx += f.dirX * 0.7;
			f.tz += f.dirZ * 0.7;
			double rest = (f.x - f.tx) * f.dirX + (f.z - f.tz) * f.dirZ; // noch zu laufende Strecke
			if (rest <= 1.4) {
				f.tx = f.x - f.dirX * 1.4;
				f.tz = f.z - f.dirZ * 1.4;
				f.phase = 1;
				f.nachTreffer = 0;
				fake.setPos(f.tx, f.bodenY, f.tz);
				schwertStoss(level, f, true);
				return false;
			}
			fake.setPos(f.tx, f.bodenY, f.tz);
			return false;
		}
		f.nachTreffer++;
		fake.setPos(f.tx, f.bodenY, f.tz);
		if (f.nachTreffer == 14 || f.nachTreffer == 28) {
			schwertStoss(level, f, false);
		}
		if (f.nachTreffer == 40) {
			HeavensWrathConfig.spieleSound(level, f.x, f.bodenY, f.z);
			koerperEffekt(level, f);
		}
		return f.nachTreffer > 75;
	}

	/** Ein Mace-Schlag des Fake-Spielers (der erste mit Einschlag-Welle). */
	private void maceSchlag(ClientLevel level, Fallend f, boolean gross) {
		double hy = f.bodenY + 1.0;
		if (f.entity instanceof LivingEntity le) {
			le.swing(InteractionHand.MAIN_HAND);
		}
		level.playLocalSound(f.x, hy, f.z, SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, 6.0f, gross ? 0.7f : 1.0f, false);
		level.playLocalSound(f.x, hy, f.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 3.0f, 0.9f, false);
		if (gross) {
			level.addParticle(ParticleTypes.EXPLOSION_EMITTER, f.x, f.bodenY + 0.3, f.z, 0.0, 0.0, 0.0);
			for (int i = 0; i < 40; i++) {
				double w = Math.random() * Math.PI * 2;
				level.addParticle(ParticleTypes.CLOUD, f.x, f.bodenY + 0.2, f.z, Math.cos(w) * 0.4, 0.05, Math.sin(w) * 0.4);
			}
		}
		for (int i = 0; i < 30; i++) {
			level.addParticle(i % 2 == 0 ? ParticleTypes.CRIT : ParticleTypes.DAMAGE_INDICATOR, f.x, hy, f.z,
					(Math.random() - 0.5) * 1.0, Math.random() * 0.6, (Math.random() - 0.5) * 1.0);
		}
		eventSenden(f.koerper, 2); // Treffer-Animation des Körpers
	}

	/** Der Fake-Spieler rammt das Schwert durch den Körper (beim ersten Stoß erscheint das riesige Schwert). */
	private void schwertStoss(ClientLevel level, Fallend f, boolean neu) {
		double hy = f.bodenY + 1.0;
		if (f.entity instanceof LivingEntity le) {
			le.swing(InteractionHand.MAIN_HAND);
			if (neu) {
				le.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); // das riesige Schwert ist sein Schwert
			}
		}
		if (neu && !f.stack.isEmpty()) {
			// Blatt zeigt vom Fake-Spieler weg, einmal quer durch den Körper.
			float psi = (float) Math.atan2(-f.dirZ, f.dirX);
			Transformation trafo = new Transformation(
					new Vector3f(0, 0, 0),
					new Quaternionf().rotateY(psi).rotateZ((float) Math.toRadians(-135.0)),
					new Vector3f(3.0f, 3.0f, 3.0f),
					new Quaternionf());
			Display.ItemDisplay schwert = erzeugeDisplay(level, f.stack, trafo, Display.BillboardConstraints.FIXED);
			if (schwert != null) {
				schwert.setId(nextClientId--);
				schwert.setPos(f.x + f.dirX * 1.1, hy, f.z + f.dirZ * 1.1);
				level.addEntity(schwert);
				f.extra = schwert;
			}
		}
		level.playLocalSound(f.x, hy, f.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 4.0f, 0.8f, false);
		level.playLocalSound(f.x, hy, f.z, SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, 3.0f, 0.7f, false);
		for (int i = 0; i < 30; i++) {
			level.addParticle(i % 3 == 0 ? ParticleTypes.DAMAGE_INDICATOR : ParticleTypes.CRIT, f.x, hy, f.z,
					(Math.random() - 0.5) * 1.0, Math.random() * 0.5, (Math.random() - 0.5) * 1.0);
		}
		eventSenden(f.koerper, 2);
	}

	/** Spielt eine Tier-Animation ab (Entity-Event); falsche IDs sind harmlos. */
	private static void eventSenden(Entity e, int id) {
		try {
			e.handleEntityEvent((byte) id);
		} catch (Throwable ignored) {
			// nur Optik
		}
	}

	private static double abstandZumOpfer(Fallend f) {
		double dx = f.x - f.tx;
		double dz = f.z - f.tz;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** Gibt true zurück, wenn der Angriff komplett vorbei ist. */
	private boolean aktualisiereAngriff(ClientLevel level, Fallend f) {
		f.zaehler++;
		if (f.alter > 400) {
			return true;
		}
		if (f.art.startsWith("assist")) {
			return aktualisiereAssist(level, f);
		}
		double kopfY = f.bodenY + 1.6;
		switch (f.art) {
			case "ziege" -> {
				if (f.zaehler == 1) {
					eventSenden(f.entity, 58); // Kopf senken
				}
				if (f.phase == 0 || f.nachTreffer < 15) {
					f.tx += f.dirX * ZIEGE_TEMPO;
					f.tz += f.dirZ * ZIEGE_TEMPO;
					level.addParticle(ParticleTypes.POOF, f.tx, f.bodenY + 0.1, f.tz, 0.0, 0.02, 0.0);
				}
				if (f.phase == 0 && abstandZumOpfer(f) < 1.8) {
					level.playLocalSound(f.x, f.bodenY, f.z, SoundEvents.GOAT_RAM_IMPACT, SoundSource.HOSTILE, 3.0f, 1.0f, false);
					treffer(level, f, f.dirX * 1.1, 0.6, f.dirZ * 1.1, 0.08, 45, true);
				}
			}
			case "lama" -> {
				if (f.zaehler == 20) {
					level.playLocalSound(f.tx, kopfY, f.tz, SoundEvents.LLAMA_SPIT, SoundSource.HOSTILE, 3.0f, 1.0f, false);
				}
				if (f.zaehler >= 20 && f.zaehler < 28 && f.phase == 0) {
					double t = (f.zaehler - 20) / 8.0;
					for (int i = 0; i < 3; i++) {
						level.addParticle(ParticleTypes.SPIT,
								f.tx + (f.x - f.tx) * t + (Math.random() - 0.5) * 0.15,
								kopfY + (f.bodenY + 1.2 - kopfY) * t + (Math.random() - 0.5) * 0.15,
								f.tz + (f.z - f.tz) * t + (Math.random() - 0.5) * 0.15,
								0.0, 0.0, 0.0);
					}
				}
				if (f.zaehler == 28 && f.phase == 0) {
					for (int i = 0; i < 30; i++) {
						level.addParticle(ParticleTypes.SPIT, f.x, f.bodenY + 1.2, f.z,
								(Math.random() - 0.5) * 0.4, Math.random() * 0.3, (Math.random() - 0.5) * 0.4);
					}
					treffer(level, f, f.dirX * 1.7, 0.9, f.dirZ * 1.7, 0.06, 50, false);
				}
			}
			case "warden" -> {
				if (f.zaehler == 1) {
					eventSenden(f.entity, 62); // Sonic-Boom-Animation
					level.playLocalSound(f.tx, kopfY, f.tz, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 4.0f, 1.0f, false);
				}
				if (f.zaehler == 34 && f.phase == 0) {
					level.playLocalSound(f.tx, kopfY, f.tz, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 6.0f, 1.0f, false);
					double dx = f.x - f.tx;
					double dz = f.z - f.tz;
					double dist = Math.sqrt(dx * dx + dz * dz);
					for (int i = 1; i <= (int) dist; i++) {
						double t = i / dist;
						level.addParticle(ParticleTypes.SONIC_BOOM, f.tx + dx * t,
								kopfY + (f.bodenY + 1.0 - kopfY) * t, f.tz + dz * t, 0.0, 0.0, 0.0);
					}
					treffer(level, f, f.dirX * 3.2, 0.3, f.dirZ * 3.2, 0.04, 35, false);
				}
			}
			case "blitz" -> { // Zeus' Zorn: drei Blitze nacheinander
				if (f.zaehler == 8 || f.zaehler == 24 || f.zaehler == 40) {
					blitz(level, f.x + (Math.random() - 0.5) * 0.6, f.bodenY, f.z + (Math.random() - 0.5) * 0.6);
				}
				if (f.zaehler == 41 && f.phase == 0) {
					f.phase = 1;
					f.nachTreffer = 0;
					HeavensWrathConfig.spieleSound(level, f.x, f.bodenY, f.z);
					koerperBrennt(f);
					koerperEffekt(level, f);
				}
			}
			case "engel" -> { // Himmelfahrt: Lichtsäule, der Körper dreht sich hinauf
				if (f.zaehler == 1) {
					f.phase = 1;
					f.flug = f.koerper != null;
					f.kvx = 0.0;
					f.kvy = 0.16;
					f.kvz = 0.0;
					f.grav = 0.0;
					f.maxFlug = 70;
					f.landet = false;
					f.flugTicks = 0;
					level.playLocalSound(f.x, f.bodenY, f.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 6.0f, 1.2f, false);
				}
				if (f.zaehler < 75) {
					for (int i = 0; i < 6; i++) {
						level.addParticle(ParticleTypes.END_ROD, f.x + (Math.random() - 0.5) * 0.5,
								f.bodenY + Math.random() * 40.0, f.z + (Math.random() - 0.5) * 0.5, 0.0, 0.0, 0.0);
					}
				}
				if (f.koerper != null) {
					float drehung = f.zaehler * 12.0f;
					f.koerper.setYRot(drehung);
					f.koerper.yRotO = drehung;
					if (f.koerper instanceof LivingEntity le) {
						le.yBodyRot = drehung;
						le.yBodyRotO = drehung;
						le.yHeadRot = drehung;
						le.yHeadRotO = drehung;
					}
				}
			}
			case "golem" -> { // Eisengolem: schleudert den Gegner in den Himmel ("zum Mond")
				if (f.zaehler == 15 && f.phase == 0) {
					eventSenden(f.entity, 4); // Schlag-Animation
					level.playLocalSound(f.tx, kopfY, f.tz, SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 3.0f, 0.8f, false);
					treffer(level, f, f.dirX * 0.15, 3.0, f.dirZ * 0.15, 0.0, 50, false);
				}
			}
		}
		f.entity.setPos(f.tx, f.bodenY, f.tz);
		fliegen(level, f);

		if (f.phase == 1) {
			f.nachTreffer++;
			return f.nachTreffer > NACHLAUF_TICKS;
		}
		return false;
	}

	/** Der Gegner wird getroffen: Treffer-Effekt, danach fliegt der Körper mit der gegebenen Geschwindigkeit los. */
	private void treffer(ClientLevel level, Fallend f, double vx, double vy, double vz, double grav, int maxFlug,
			boolean landet) {
		f.phase = 1;
		f.nachTreffer = 0;
		f.flug = f.koerper != null;
		f.kvx = vx;
		f.kvy = vy;
		f.kvz = vz;
		f.grav = grav;
		f.maxFlug = maxFlug;
		f.landet = landet;
		f.flugTicks = 0;
		for (int i = 0; i < 40; i++) {
			level.addParticle(ParticleTypes.CRIT, f.x, f.bodenY + 1.0, f.z,
					(Math.random() - 0.5) * 1.2, Math.random() * 0.8, (Math.random() - 0.5) * 1.2);
		}
		level.addParticle(ParticleTypes.EXPLOSION, f.x, f.bodenY + 1.0, f.z, 0.0, 0.0, 0.0);
	}

	private void fliegen(ClientLevel level, Fallend f) {
		if (!f.flug || f.koerper == null) {
			return;
		}
		Entity k = f.koerper;
		f.kvy -= f.grav;
		f.flugTicks++;
		double nx = k.getX() + f.kvx;
		double ny = k.getY() + f.kvy;
		double nz = k.getZ() + f.kvz;

		if (f.landet && ny <= f.bodenY && f.kvy < 0) {
			k.setPos(nx, f.bodenY, nz);
			f.flug = false;
			return;
		}
		if (f.flugTicks >= f.maxFlug) {
			verpuffen(level, f, nx, ny, nz);
			k.discard();
			f.koerper = null;
			f.flug = false;
			return;
		}
		k.setPos(nx, ny, nz);
		level.addParticle("golem".equals(f.art) ? ParticleTypes.FIREWORK
				: "engel".equals(f.art) ? ParticleTypes.END_ROD : ParticleTypes.CLOUD,
				nx, ny + 1.0, nz, 0.0, 0.0, 0.0);
	}

	/** Ende des Flugs: Der Gegner verschwindet (beim Golem als Feuerwerk am Himmel). */
	private void verpuffen(ClientLevel level, Fallend f, double x, double y, double z) {
		if ("engel".equals(f.art)) {
			level.playLocalSound(x, y, z, SoundEvents.TOTEM_USE, SoundSource.HOSTILE, 8.0f, 1.0f, false);
			for (int i = 0; i < 80; i++) {
				level.addParticle(ParticleTypes.TOTEM_OF_UNDYING, x, y + 1.0, z,
						(Math.random() - 0.5) * 1.4, Math.random() * 1.2, (Math.random() - 0.5) * 1.4);
			}
			return;
		}
		if ("golem".equals(f.art)) {
			level.playLocalSound(x, y, z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.HOSTILE, 8.0f, 0.8f, false);
			for (int i = 0; i < 60; i++) {
				level.addParticle(ParticleTypes.FIREWORK, x, y + 1.0, z,
						(Math.random() - 0.5) * 1.2, (Math.random() - 0.5) * 1.2, (Math.random() - 0.5) * 1.2);
			}
		} else {
			for (int i = 0; i < 30; i++) {
				level.addParticle(ParticleTypes.POOF, x, y + 1.0, z,
						(Math.random() - 0.5) * 0.6, (Math.random() - 0.5) * 0.6, (Math.random() - 0.5) * 0.6);
			}
		}
	}

	/** Beim Einschlag: Körper löst sich auf oder verliert nur Rüstung und Waffen (siehe /killeffect). */
	private void koerperEffekt(ClientLevel level, Fallend f) {
		if (HeavensWrathConfig.koerperAufloesen) {
			koerperLoestAuf(level, f);
		} else {
			ruestungVerschwindet(level, f);
		}
	}

	/**
	 * Beim Einschlag verschwinden Rüstung und Waffen der stehenden Kopie einfach am
	 * Körper (mit etwas Rauch). Die Kopie selbst bleibt (nackt) stehen, bis die Waffe weg ist.
	 */
	private void ruestungVerschwindet(ClientLevel level, Fallend f) {
		if (!(f.koerper instanceof LivingEntity opfer)) {
			return;
		}
		double x = opfer.getX();
		double y = opfer.getY();
		double z = opfer.getZ();

		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (opfer.getItemBySlot(slot).isEmpty()) {
				continue;
			}
			double hoehe = switch (slot) {
				case HEAD -> 1.7;
				case CHEST -> 1.2;
				case LEGS -> 0.7;
				case FEET -> 0.2;
				default -> 1.0; // Haupt- und Nebenhand
			};
			for (int i = 0; i < 6; i++) {
				level.addParticle(ParticleTypes.POOF, x, y + hoehe, z,
						(Math.random() - 0.5) * 0.2, 0.05 + Math.random() * 0.1, (Math.random() - 0.5) * 0.2);
			}
			opfer.setItemSlot(slot, ItemStack.EMPTY);
		}
	}

	/** Der Körper zerfällt in Asche und Seelen-Partikel und ist weg. */
	private void koerperLoestAuf(ClientLevel level, Fallend f) {
		if (f.koerper == null) {
			return;
		}
		double x = f.koerper.getX();
		double y = f.koerper.getY();
		double z = f.koerper.getZ();
		for (int i = 0; i < 100; i++) {
			double hoehe = Math.random() * 1.9;
			level.addParticle(i % 2 == 0 ? ParticleTypes.ASH : ParticleTypes.SOUL,
					x + (Math.random() - 0.5) * 0.7, y + hoehe, z + (Math.random() - 0.5) * 0.7,
					(Math.random() - 0.5) * 0.15, 0.05 + Math.random() * 0.12, (Math.random() - 0.5) * 0.15);
		}
		for (int i = 0; i < 25; i++) {
			level.addParticle(ParticleTypes.LARGE_SMOKE, x + (Math.random() - 0.5) * 0.6, y + Math.random() * 1.8,
					z + (Math.random() - 0.5) * 0.6, 0.0, 0.08, 0.0);
		}
		f.koerper.discard();
		f.koerper = null;
	}

	/** Stufe von Verbrennung (Fire Aspect) auf der Waffe; 0 = nicht verzaubert. */
	private static int feuerStufe(ItemStack waffe) {
		try {
			ItemEnchantments verzauberungen = waffe.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
			for (Holder<Enchantment> h : verzauberungen.keySet()) {
				if (h.is(Enchantments.FIRE_ASPECT)) {
					return Math.max(1, verzauberungen.getLevel(h));
				}
			}
		} catch (Throwable t) {
			System.err.println("[Heaven's Wrath] Verbrennung nicht erkannt: " + t);
		}
		return 0;
	}

	/** Flammen und Rauch um die brennende Waffe, während sie fällt (Stufe 2+ deutlich mehr). */
	private void feuerFlug(ClientLevel level, Fallend f) {
		boolean stark = f.feuer >= 2;
		int anzahl = stark ? 18 : 6;
		double breite = SKALIERUNG * 0.3;
		double hoehe = SKALIERUNG * 0.6;
		for (int i = 0; i < anzahl; i++) {
			double px = f.x + (Math.random() - 0.5) * breite * 2;
			double py = f.y + (Math.random() - 0.5) * hoehe * 2;
			double pz = f.z + (Math.random() - 0.5) * breite * 2;
			level.addParticle(ParticleTypes.FLAME, px, py, pz, 0.0, 0.05, 0.0);
			if (i % 3 == 0) {
				level.addParticle(ParticleTypes.SMOKE, px, py, pz, 0.0, 0.08, 0.0);
			}
			if (stark && i % 4 == 0) {
				level.addParticle(ParticleTypes.LAVA, px, py, pz, 0.0, 0.0, 0.0);
				level.addParticle(ParticleTypes.LARGE_SMOKE, px, py, pz, 0.0, 0.1, 0.0);
			}
		}
	}

	/** Feuerwelle beim Einschlag; der Körper fängt Feuer. Stufe 2+: größere Welle, Feuersäule, Lava. */
	private void feuerEinschlag(ClientLevel level, Fallend f, double x, double y, double z) {
		boolean stark = f.feuer >= 2;
		level.playLocalSound(x, y, z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 4.0f, 0.5f, false);
		if (stark) {
			level.playLocalSound(x, y, z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 4.0f, 0.5f, false);
		}

		double radius = stark ? 4.5 : 2.5;
		int ring = stark ? 120 : 40;
		for (int i = 0; i < ring; i++) {
			double winkel = Math.random() * Math.PI * 2;
			double r = Math.random() * radius;
			double dx = Math.cos(winkel);
			double dz = Math.sin(winkel);
			level.addParticle(ParticleTypes.FLAME, x + dx * r, y + 0.1, z + dz * r,
					dx * 0.15, 0.1 + Math.random() * 0.2, dz * 0.15);
		}
		if (stark) {
			for (int i = 0; i < 40; i++) {
				level.addParticle(ParticleTypes.FLAME, x + (Math.random() - 0.5) * 1.2, y + i * 0.35,
						z + (Math.random() - 0.5) * 1.2, 0.0, 0.2, 0.0);
			}
			for (int i = 0; i < 30; i++) {
				level.addParticle(ParticleTypes.LAVA, x + (Math.random() - 0.5) * radius, y + 0.3,
						z + (Math.random() - 0.5) * radius, 0.0, 0.0, 0.0);
			}
		}
		koerperBrennt(f);
	}

	/** Flammen am Boden und am Körper, solange die Waffe im Boden steckt. */
	private void feuerAmBoden(ClientLevel level, Fallend f) {
		boolean stark = f.feuer >= 2;
		double radius = stark ? 3.5 : 1.8;
		int anzahl = stark ? 14 : 5;
		for (int i = 0; i < anzahl; i++) {
			double winkel = Math.random() * Math.PI * 2;
			double r = Math.random() * radius;
			level.addParticle(ParticleTypes.FLAME, f.x + Math.cos(winkel) * r, f.bodenY + 0.1,
					f.z + Math.sin(winkel) * r, 0.0, 0.05 + Math.random() * 0.08, 0.0);
			if (stark && i % 3 == 0) {
				level.addParticle(ParticleTypes.LARGE_SMOKE, f.x + Math.cos(winkel) * r, f.bodenY + 0.5,
						f.z + Math.sin(winkel) * r, 0.0, 0.08, 0.0);
			}
		}
		koerperBrennt(f);
	}

	/** Lässt die stehende Kopie sichtbar brennen (Feuer-Overlay). */
	private void koerperBrennt(Fallend f) {
		if (f.koerper == null) {
			return;
		}
		try {
			Method m = Entity.class.getDeclaredMethod("setSharedFlagOnFire", boolean.class);
			m.setAccessible(true);
			m.invoke(f.koerper, true);
		} catch (Throwable t) {
			// Nur Optik – ohne Feuer-Overlay läuft alles andere weiter.
		}
	}

	/** Einschlag mit dem per /killsound gewählten Sound (Standard: Wither-Spawn). */
	private void einschlag(ClientLevel level, double x, double y, double z) {
		HeavensWrathConfig.spieleSound(level, x, y, z);

		level.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.5, z, 0, 0, 0);
		for (int i = 0; i < 60; i++) {
			level.addParticle(ParticleTypes.CRIT, x, y + 0.3, z,
					(Math.random() - 0.5) * 1.6, Math.random() * 1.0, (Math.random() - 0.5) * 1.6);
		}
		for (int i = 0; i < 25; i++) {
			level.addParticle(ParticleTypes.POOF, x, y + 0.2, z,
					(Math.random() - 0.5) * 0.5, 0.05, (Math.random() - 0.5) * 0.5);
		}
	}
}
