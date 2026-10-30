package de.heavenswrath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Einstellungen der Mod (werden in config/heavens_wrath.properties gespeichert). */
public final class HeavensWrathConfig {

	/** Auswahl für /killeffect item ...  (Name -> Item). */
	public static final Map<String, Item> ITEMS = new LinkedHashMap<>();
	static {
		ITEMS.put("netherite_schwert", Items.NETHERITE_SWORD);
		ITEMS.put("diamant_schwert", Items.DIAMOND_SWORD);
		ITEMS.put("eisen_schwert", Items.IRON_SWORD);
		ITEMS.put("gold_schwert", Items.GOLDEN_SWORD);
		ITEMS.put("netherite_axt", Items.NETHERITE_AXE);
		ITEMS.put("netherite_spitzhacke", Items.NETHERITE_PICKAXE);
		ITEMS.put("dreizack", Items.TRIDENT);
		ITEMS.put("streitkolben", Items.MACE);
		ITEMS.put("bogen", Items.BOW);
		ITEMS.put("amboss", Items.ANVIL);
	}

	/** Auswahl für /killsound ...  (Name -> Sound). Dazu kommen "kombi" und "aus". */
	public static final Map<String, SoundEvent> SOUNDS = new LinkedHashMap<>();
	static {
		SOUNDS.put("wither", SoundEvents.WITHER_SPAWN);
		SOUNDS.put("wither_tod", SoundEvents.WITHER_DEATH);
		SOUNDS.put("donner", SoundEvents.LIGHTNING_BOLT_THUNDER);
		SOUNDS.put("blitz", SoundEvents.LIGHTNING_BOLT_IMPACT);
		SOUNDS.put("drache", SoundEvents.ENDER_DRAGON_GROWL);
		SOUNDS.put("drachen_tod", SoundEvents.ENDER_DRAGON_DEATH);
		SOUNDS.put("amboss", SoundEvents.ANVIL_LAND);
		SOUNDS.put("warden", SoundEvents.WARDEN_SONIC_BOOM);
		SOUNDS.put("dreizack", sound(SoundEvents.TRIDENT_THUNDER));
	}

	/** Alle Kill-Effekte in Freischalt-Reihenfolge (Battle Pass: siehe {@link HeavensWrathPass}). */
	public static final List<String> MODI = List.of("waffe", "ziege", "lama", "assist", "warden", "golem", "blitz",
			"meteor", "engel");

	/** Anzahl der Profile. */
	public static final int PROFILE = 5;

	/** Ein Profil speichert alle Auswahl-Einstellungen. */
	private static final class Profil {
		String item = "waffe";
		String sound = "wither";
		String modus = "waffe";
		String assistSkin = "";
		boolean koerperAufloesen = false;
	}

	private static final Profil[] PROFIL_DATEN = new Profil[PROFILE];
	static {
		for (int i = 0; i < PROFILE; i++) {
			PROFIL_DATEN[i] = new Profil();
		}
	}

	/** Effekt an/aus (gilt für alle Profile). */
	public static boolean aktiv = true;
	/** Deine Kills insgesamt (Battle-Pass-Fortschritt, bleibt nach Neustart erhalten). */
	public static int kills = 0;
	/** Nummer des aktuellen Profils (0 bis PROFILE-1). */
	public static int profil = 0;

	// ---- Werte des AKTUELLEN Profils ----
	/** "waffe" = das Item, mit dem du getötet hast; sonst ein Name aus ITEMS. */
	public static String item = "waffe";
	/** Einschlag-Sound: Name aus SOUNDS, "kombi" oder "aus". */
	public static String sound = "wither";
	/** Kill-Effekt aus MODI (nur freigeschaltete sind wählbar). */
	public static String modus = "waffe";
	/** Spielername, dessen Skin der Assist-Spieler trägt (leer = dein eigener Skin). */
	public static String assistSkin = "";
	/** false = Körper bleibt stehen, true = Körper löst sich beim Einschlag auf. */
	public static boolean koerperAufloesen = false;

	private HeavensWrathConfig() {
	}

	/** Manche Sounds sind je nach Version ein SoundEvent oder ein Holder davon; beides wird zum SoundEvent. */
	private static SoundEvent sound(Object o) {
		return o instanceof net.minecraft.core.Holder<?> h ? (SoundEvent) h.value() : (SoundEvent) o;
	}

	/** Alle wählbaren Sound-Namen für das Menü. */
	public static List<String> soundNamen() {
		List<String> liste = new ArrayList<>();
		liste.add("aus");
		liste.add("kombi");
		liste.addAll(SOUNDS.keySet());
		return liste;
	}

	/** Spielt den gewählten Einschlag-Sound ab. */
	public static void spieleSound(ClientLevel level, double x, double y, double z) {
		if ("aus".equals(sound)) {
			return;
		}
		if ("kombi".equals(sound)) {
			level.playLocalSound(x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 8.0f, 0.6f, false);
			level.playLocalSound(x, y, z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 6.0f, 0.5f, false);
			level.playLocalSound(x, y, z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 4.0f, 0.4f, false);
			level.playLocalSound(x, y, z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 6.0f, 1.0f, false);
			return;
		}
		SoundEvent event = SOUNDS.getOrDefault(sound, SoundEvents.WITHER_SPAWN);
		// Lautstärke > 1 = weit hörbar (wie der globale Wither-Spawn-Sound).
		float lautstaerke = event == SoundEvents.WITHER_SPAWN ? 10.0f : 8.0f;
		level.playLocalSound(x, y, z, event, SoundSource.HOSTILE, lautstaerke, 1.0f, false);
	}

	/** Spielt den Sound zur Probe an deiner Position ab (beim Wechseln im Menü). */
	public static void vorschau() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.player != null) {
			spieleSound(mc.level, mc.player.getX(), mc.player.getY(), mc.player.getZ());
		}
	}

	/** Welches Item soll fallen? */
	public static ItemStack waehle(ItemStack gehalten) {
		Item preset = ITEMS.get(item);
		return preset == null ? gehalten : new ItemStack(preset);
	}

	private static Path datei() {
		return FabricLoader.getInstance().getConfigDir().resolve("heavens_wrath.properties");
	}

	private static void aktuellInProfil() {
		Profil p = PROFIL_DATEN[profil];
		p.item = item;
		p.sound = sound;
		p.modus = modus;
		p.assistSkin = assistSkin;
		p.koerperAufloesen = koerperAufloesen;
	}

	private static void profilInAktuell() {
		Profil p = PROFIL_DATEN[profil];
		item = p.item;
		sound = p.sound;
		modus = p.modus;
		assistSkin = p.assistSkin;
		koerperAufloesen = p.koerperAufloesen;
	}

	/** Wechselt das Profil (speichert vorher das aktuelle). */
	public static void waehleProfil(int nummer) {
		if (nummer < 0 || nummer >= PROFILE) {
			return;
		}
		aktuellInProfil();
		profil = nummer;
		profilInAktuell();
		speichern();
	}

	/** Zusammenfassung eines Profils für die Anzeige. */
	public static String profilKurz(int nummer) {
		if (nummer == profil) {
			aktuellInProfil();
		}
		Profil p = PROFIL_DATEN[nummer];
		return p.modus + " | " + ("waffe".equals(p.item) ? "deine Waffe" : p.item.replace('_', ' ')) + " | "
				+ p.sound.replace('_', ' ');
	}

	public static String profilModus(int nummer) {
		if (nummer == profil) {
			aktuellInProfil();
		}
		return PROFIL_DATEN[nummer].modus;
	}

	/** Name aus dem GameProfile, egal ob die Bibliothek name() oder getName() anbietet (ändert sich je Version). */
	public static String profilName(Object profil) {
		if (profil == null) {
			return "";
		}
		for (String methode : new String[] { "name", "getName" }) {
			try {
				Method m = profil.getClass().getMethod(methode);
				Object r = m.invoke(profil);
				if (r instanceof String s) {
					return s;
				}
			} catch (Throwable ignored) {
				// nächste Variante probieren
			}
		}
		return "";
	}

	/** Namen der Spieler auf dem Server (für die Skin-Wahl des Assist-Spielers). */
	public static List<String> onlineNamen() {
		List<String> liste = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null) {
			for (var info : mc.getConnection().getOnlinePlayers()) {
				String n = profilName(info.getProfile());
				if (n.matches("[A-Za-z0-9_]{1,16}") && !liste.contains(n)) {
					liste.add(n);
				}
			}
		}
		java.util.Collections.sort(liste, String.CASE_INSENSITIVE_ORDER);
		return liste;
	}

	private static String gueltig(String wert, String standard, java.util.function.Predicate<String> ok) {
		return wert != null && ok.test(wert) ? wert : standard;
	}

	public static void laden() {
		Path p = datei();
		if (!Files.exists(p)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(p)) {
			Properties props = new Properties();
			props.load(r);
			aktiv = Boolean.parseBoolean(props.getProperty("aktiv", "true"));
			try {
				kills = Math.max(0, Integer.parseInt(props.getProperty("kills", "0").trim()));
			} catch (NumberFormatException e) {
				kills = 0;
			}
			try {
				profil = Math.max(0, Math.min(PROFILE - 1, Integer.parseInt(props.getProperty("profil", "0").trim())));
			} catch (NumberFormatException e) {
				profil = 0;
			}
			for (int i = 0; i < PROFILE; i++) {
				Profil pr = PROFIL_DATEN[i];
				// Alte Einstellungen (vor den Profilen) gehören zu Profil 1.
				String k = "p" + i + ".";
				boolean alt = i == 0 && props.getProperty(k + "modus") == null;
				pr.item = gueltig(props.getProperty(alt ? "item" : k + "item"), "waffe",
						v -> "waffe".equals(v) || ITEMS.containsKey(v));
				pr.sound = gueltig(props.getProperty(alt ? "sound" : k + "sound"), "wither",
						v -> "aus".equals(v) || "kombi".equals(v) || SOUNDS.containsKey(v));
				pr.modus = gueltig(props.getProperty(alt ? "modus" : k + "modus"), "waffe",
						v -> MODI.contains(v) && HeavensWrathPass.freigeschaltet(v));
				pr.assistSkin = props.getProperty(k + "assistSkin", "").trim();
				if (!pr.assistSkin.matches("[A-Za-z0-9_]{0,16}")) {
					pr.assistSkin = "";
				}
				pr.koerperAufloesen = Boolean.parseBoolean(
						props.getProperty(alt ? "koerperAufloesen" : k + "koerperAufloesen", "false"));
			}
			profilInAktuell();
		} catch (IOException e) {
			System.err.println("[Heaven's Wrath] Einstellungen nicht lesbar: " + e);
		}
	}

	public static void speichern() {
		aktuellInProfil();
		Properties props = new Properties();
		props.setProperty("aktiv", Boolean.toString(aktiv));
		props.setProperty("kills", Integer.toString(kills));
		props.setProperty("profil", Integer.toString(profil));
		for (int i = 0; i < PROFILE; i++) {
			Profil pr = PROFIL_DATEN[i];
			String k = "p" + i + ".";
			props.setProperty(k + "item", pr.item);
			props.setProperty(k + "sound", pr.sound);
			props.setProperty(k + "modus", pr.modus);
			props.setProperty(k + "assistSkin", pr.assistSkin);
			props.setProperty(k + "koerperAufloesen", Boolean.toString(pr.koerperAufloesen));
		}
		// Erst in eine Temp-Datei schreiben, dann ersetzen: so geht der Fortschritt nie halb verloren.
		Path ziel = datei();
		Path temp = ziel.resolveSibling("heavens_wrath.properties.tmp");
		try (Writer w = Files.newBufferedWriter(temp)) {
			props.store(w, "Heaven's Wrath - Fortschritt (kills) und Profile");
		} catch (IOException e) {
			System.err.println("[Heaven's Wrath] Einstellungen nicht speicherbar: " + e);
			return;
		}
		try {
			Files.move(temp, ziel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			System.err.println("[Heaven's Wrath] Einstellungen nicht ersetzbar: " + e);
		}
	}
}
