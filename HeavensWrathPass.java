package de.heavenswrath;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Battle Pass: Alle 10 Kills wird ein neuer Kill-Effekt freigeschaltet. Der Fortschritt steht in der Config-Datei. */
public final class HeavensWrathPass {

	public static final int KILLS_PRO_STUFE = 10;

	/** Eine Belohnung: ab wie vielen Kills, welcher Kill-Effekt, Anzeige-Name, Beschreibung, Symbol. */
	public record Stufe(int kills, String modus, String name, String info, Item icon) {
	}

	public static final List<Stufe> STUFEN = List.of(
			new Stufe(10, "ziege", "Ziegen-Rammbock", "Eine Ziege rammt ihn weg", Items.GOAT_HORN),
			new Stufe(20, "lama", "Lama-Spucke", "Ein Lama spuckt ihn weg", Items.LEAD),
			new Stufe(30, "assist", "Assist-Kill", "Mace/Schwert: Fake-Spieler hilft", Items.MACE),
			new Stufe(40, "warden", "Sonic Boom", "Der Warden schießt ihn weg", Items.ECHO_SHARD),
			new Stufe(50, "golem", "Mond-Schleuder", "Der Golem schleudert ihn", Items.IRON_BLOCK),
			new Stufe(60, "blitz", "Zeus' Zorn", "Drei Blitze vom Himmel", Items.LIGHTNING_ROD),
			new Stufe(70, "meteor", "Meteor", "Ein Feuerball schlägt ein", Items.MAGMA_BLOCK),
			new Stufe(80, "engel", "Himmelfahrt", "Lichtsäule trägt ihn hinauf", Items.TOTEM_OF_UNDYING));

	private HeavensWrathPass() {
	}

	/** Der Standard-Effekt "waffe" ist immer frei, alle anderen ab ihrer Kill-Zahl. */
	public static boolean freigeschaltet(String modus) {
		if ("waffe".equals(modus)) {
			return true;
		}
		for (Stufe s : STUFEN) {
			if (s.modus().equals(modus)) {
				return HeavensWrathConfig.kills >= s.kills();
			}
		}
		return false;
	}

	public static Stufe stufe(String modus) {
		for (Stufe s : STUFEN) {
			if (s.modus().equals(modus)) {
				return s;
			}
		}
		return null;
	}

	public static String anzeigeName(String modus) {
		Stufe s = stufe(modus);
		return s == null ? "Heaven's Wrath (Waffe)" : s.name();
	}

	public static Item icon(String modus) {
		Stufe s = stufe(modus);
		return s == null ? Items.DIAMOND_SWORD : s.icon();
	}

	/** Anzahl freigeschalteter Stufen. */
	public static int freigeschalteteStufen() {
		int n = 0;
		for (Stufe s : STUFEN) {
			if (HeavensWrathConfig.kills >= s.kills()) {
				n++;
			}
		}
		return n;
	}

	/** Nächste noch gesperrte Stufe oder null, wenn alles frei ist. */
	public static Stufe naechste() {
		for (Stufe s : STUFEN) {
			if (HeavensWrathConfig.kills < s.kills()) {
				return s;
			}
		}
		return null;
	}

	/** Ein Kill mehr: speichern und freigeschaltete Belohnungen im Chat melden. */
	public static void kill(Minecraft mc) {
		int vorher = HeavensWrathConfig.kills;
		HeavensWrathConfig.kills = vorher + 1;
		HeavensWrathConfig.speichern();
		for (Stufe s : STUFEN) {
			if (vorher < s.kills() && HeavensWrathConfig.kills >= s.kills() && mc.player != null) {
				melde(mc, Component.literal("Battle Pass: " + s.name() + " freigeschaltet! ("
						+ s.info() + ") Wähle ihn mit /killeffect oder Taste K.").withStyle(ChatFormatting.GOLD));
				mc.player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
			}
		}
	}

	/** Schreibt eine Nachricht in deinen Chat (nur bei dir). */
	private static void melde(Minecraft mc, Component nachricht) {
		// <MELDEN>
		// Der Name dieser Methode ändert sich zwischen Versionen; darum werden die bekannten Namen probiert.
		Object chat = null;
		try {
			chat = mc.gui.getClass().getMethod("getChat").invoke(mc.gui);
		} catch (Throwable ignored) {
			// kein Chat-Objekt
		}
		if (versuche(mc.player, "sendSystemMessage", nachricht) || versuche(mc.player, "displayClientMessage", nachricht, false)
				|| versuche(chat, "addClientSystemMessage", nachricht) || versuche(chat, "addMessage", nachricht)) {
			return;
		}
		System.err.println("[Heaven's Wrath] Chat-Nachricht nicht möglich: " + nachricht.getString());
		// </MELDEN>
	}

	private static boolean versuche(Object ziel, String name, Object... args) {
		if (ziel == null) {
			return false;
		}
		for (java.lang.reflect.Method m : ziel.getClass().getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == args.length) {
				try {
					m.invoke(ziel, args);
					return true;
				} catch (Throwable ignored) {
					// nächste Variante
				}
			}
		}
		return false;
	}
}
