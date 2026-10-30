package de.heavenswrath;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Das Heaven's Wrath Menü (Befehl /killeffect oder Hotkey, Standard K).
 * Vier Seiten: Effekte, Items, Sounds, Battle Pass. Oben die 5 Profile, unten die Schalter.
 * Alle Karten sind unsichtbare Knöpfe, über die das Menü eigene Bilder mit Items zeichnet.
 */
public class HeavensWrathScreen extends Screen {

	private static final int B = 360;
	private static final int H = 250;

	private static final int GOLD = 0xFFFFC83D;
	private static final int WEISS = 0xFFFFFFFF;
	private static final int GRAU = 0xFFAAAAAA;
	private static final int DUNKEL = 0xFF777788;
	private static final int GRUEN = 0xFF66FF66;
	private static final int ROT = 0xFFFF6666;
	private static final int KARTE = 0xFF22223A;
	private static final int KARTE_HOVER = 0xFF33335C;
	private static final int KARTE_GEWAEHLT = 0xFF244A2E;
	private static final int KARTE_GESPERRT = 0xFF2A1A1F;

	/** Wird vom Befehl angefordert (Seite 0 bis 3) und im nächsten Tick geöffnet; -1 = nichts. */
	public static int angefordert = -1;

	private static final String[] SEITEN = { "Effekte", "Items", "Sounds", "Battle Pass" };

	private interface Zeichner {
		void zeichne(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover);
	}

	private record Karte(Button knopf, int x, int y, int w, int h, Zeichner zeichner) {
	}

	private final List<Karte> karten = new ArrayList<>();
	private int seite;
	private int links;
	private int oben;

	public HeavensWrathScreen(int seite) {
		super(Component.literal("Heaven's Wrath"));
		this.seite = Math.max(0, Math.min(SEITEN.length - 1, seite));
	}

	public static void pruefeOeffnen(Minecraft mc) {
		if (angefordert >= 0) {
			int s = angefordert;
			angefordert = -1;
			mc.setScreen(new HeavensWrathScreen(s));
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------
	// Aufbau
	// ------------------------------------------------------------------

	@Override
	protected void init() {
		karten.clear();
		links = (this.width - B) / 2;
		oben = (this.height - H) / 2;

		// Profile 1-5
		for (int i = 0; i < HeavensWrathConfig.PROFILE; i++) {
			int n = i;
			karte(links + 52 + i * 30, oben + 22, 26, 16, () -> HeavensWrathConfig.waehleProfil(n),
					(g, x, y, w, h, hover) -> {
						boolean aktuell = HeavensWrathConfig.profil == n;
						rahmen(g, x, y, w, h, aktuell ? GOLD : (hover ? KARTE_HOVER : KARTE), aktuell ? GOLD : DUNKEL);
						mitte(g, String.valueOf(n + 1), x, y, w, h, aktuell ? 0xFF1A1A1A : WEISS);
					});
		}

		// Seiten-Reiter
		for (int i = 0; i < SEITEN.length; i++) {
			int n = i;
			karte(links + 10 + i * 87, oben + 44, 83, 18, () -> {
				seite = n;
				this.rebuildWidgets();
			}, (g, x, y, w, h, hover) -> {
				boolean aktuell = seite == n;
				rahmen(g, x, y, w, h, aktuell ? 0xFF3B2A6E : (hover ? KARTE_HOVER : KARTE), aktuell ? GOLD : DUNKEL);
				mitte(g, SEITEN[n], x, y, w, h, aktuell ? GOLD : WEISS);
			});
		}

		switch (seite) {
			case 0 -> baueEffekte();
			case 1 -> baueItems();
			case 2 -> baueSounds();
			default -> bauePass();
		}

		// Fußleiste
		int fy = oben + H - 26;
		karte(links + 10, fy, 70, 20, () -> HeavensWrathConfig.aktiv = !HeavensWrathConfig.aktiv, (g, x, y, w, h, hover) -> {
			rahmen(g, x, y, w, h, hover ? KARTE_HOVER : KARTE, DUNKEL);
			mitte(g, "Effekt: " + (HeavensWrathConfig.aktiv ? "AN" : "AUS"), x, y, w, h,
					HeavensWrathConfig.aktiv ? GRUEN : ROT);
		});
		karte(links + 84, fy, 100, 20, () -> HeavensWrathConfig.koerperAufloesen = !HeavensWrathConfig.koerperAufloesen,
				(g, x, y, w, h, hover) -> {
					rahmen(g, x, y, w, h, hover ? KARTE_HOVER : KARTE, DUNKEL);
					mitte(g, "Körper: " + (HeavensWrathConfig.koerperAufloesen ? "löst auf" : "steht"), x, y, w, h, WEISS);
				});
		karte(links + 188, fy, 110, 20, this::naechsterSkin, (g, x, y, w, h, hover) -> {
			rahmen(g, x, y, w, h, hover ? KARTE_HOVER : KARTE, DUNKEL);
			String name = HeavensWrathConfig.assistSkin.isEmpty() ? "ich" : HeavensWrathConfig.assistSkin;
			mitte(g, kuerze("Assist-Skin: " + name, w - 6), x, y, w, h, WEISS);
		});
		karte(links + 302, fy, 48, 20, this::onClose, (g, x, y, w, h, hover) -> {
			rahmen(g, x, y, w, h, hover ? 0xFF3A6A3A : 0xFF2A4A2A, GRUEN);
			mitte(g, "Fertig", x, y, w, h, WEISS);
		});
	}

	/** Seite 1: Kill-Effekte (3 x 3 Karten). */
	private void baueEffekte() {
		int top = oben + 68;
		for (int i = 0; i < HeavensWrathConfig.MODI.size(); i++) {
			String m = HeavensWrathConfig.MODI.get(i);
			int x = links + 10 + (i % 3) * 115;
			int y = top + (i / 3) * 45;
			karte(x, y, 110, 40, () -> {
				if (HeavensWrathPass.freigeschaltet(m)) {
					HeavensWrathConfig.modus = m;
				}
			}, (g, bx, by, w, h, hover) -> {
				boolean frei = HeavensWrathPass.freigeschaltet(m);
				boolean gewaehlt = HeavensWrathConfig.modus.equals(m);
				rahmen(g, bx, by, w, h, !frei ? KARTE_GESPERRT : gewaehlt ? KARTE_GEWAEHLT : hover ? KARTE_HOVER : KARTE,
						gewaehlt ? GRUEN : frei ? DUNKEL : 0xFF553333);
				HeavensWrathGfx.item(g, new ItemStack(frei ? HeavensWrathPass.icon(m) : Items.BARRIER), bx + 6, by + 12);
				HeavensWrathGfx.text(g, this.font, kuerze(HeavensWrathPass.anzeigeName(m), w - 32), bx + 28, by + 9,
						frei ? WEISS : GRAU);
				String status;
				int farbe;
				if (!frei) {
					HeavensWrathPass.Stufe st = HeavensWrathPass.stufe(m);
					status = "Gesperrt: " + (st == null ? "?" : Math.max(0, st.kills() - HeavensWrathConfig.kills)) + " Kills";
					farbe = ROT;
				} else if (gewaehlt) {
					status = "Ausgewählt";
					farbe = GRUEN;
				} else {
					status = "Klick: wählen";
					farbe = GRAU;
				}
				HeavensWrathGfx.text(g, this.font, kuerze(status, w - 32), bx + 28, by + 23, farbe);
			});
		}
	}

	/** Seite 2: fallendes Item (4 Spalten). */
	private void baueItems() {
		List<String> namen = new ArrayList<>();
		namen.add("waffe");
		namen.addAll(HeavensWrathConfig.ITEMS.keySet());
		int top = oben + 68;
		for (int i = 0; i < namen.size(); i++) {
			String n = namen.get(i);
			int x = links + 10 + (i % 4) * 86;
			int y = top + (i / 4) * 45;
			Item item = "waffe".equals(n) ? Items.DIAMOND_SWORD : HeavensWrathConfig.ITEMS.get(n);
			karte(x, y, 82, 40, () -> HeavensWrathConfig.item = n, (g, bx, by, w, h, hover) -> {
				boolean gewaehlt = HeavensWrathConfig.item.equals(n);
				rahmen(g, bx, by, w, h, gewaehlt ? KARTE_GEWAEHLT : hover ? KARTE_HOVER : KARTE, gewaehlt ? GRUEN : DUNKEL);
				HeavensWrathGfx.item(g, new ItemStack(item), bx + w / 2 - 8, by + 4);
				mitte(g, kuerze("waffe".equals(n) ? "Deine Waffe" : n.replace('_', ' '), w - 4), bx, by + 22, w, 14,
						gewaehlt ? GRUEN : WEISS);
			});
		}
	}

	/** Seite 3: Einschlag-Sound (4 Spalten, Klick spielt den Sound zur Probe). */
	private void baueSounds() {
		List<String> namen = HeavensWrathConfig.soundNamen();
		int top = oben + 68;
		for (int i = 0; i < namen.size(); i++) {
			String n = namen.get(i);
			int x = links + 10 + (i % 4) * 86;
			int y = top + (i / 4) * 45;
			karte(x, y, 82, 40, () -> {
				HeavensWrathConfig.sound = n;
				HeavensWrathConfig.vorschau();
			}, (g, bx, by, w, h, hover) -> {
				boolean gewaehlt = HeavensWrathConfig.sound.equals(n);
				rahmen(g, bx, by, w, h, gewaehlt ? KARTE_GEWAEHLT : hover ? KARTE_HOVER : KARTE, gewaehlt ? GRUEN : DUNKEL);
				HeavensWrathGfx.item(g, new ItemStack("aus".equals(n) ? Items.BARRIER : Items.NOTE_BLOCK), bx + w / 2 - 8,
						by + 4);
				mitte(g, kuerze(n.replace('_', ' '), w - 4), bx, by + 22, w, 14, gewaehlt ? GRUEN : WEISS);
			});
		}
	}

	/** Seite 4: Battle Pass (Fortschrittsbalken und alle Belohnungen). */
	private void bauePass() {
		int top = oben + 68 + 26;
		List<HeavensWrathPass.Stufe> stufen = HeavensWrathPass.STUFEN;
		for (int i = 0; i < stufen.size(); i++) {
			HeavensWrathPass.Stufe st = stufen.get(i);
			int x = links + 10 + (i % 4) * 86;
			int y = top + (i / 4) * 58;
			karte(x, y, 82, 54, () -> {
				if (HeavensWrathPass.freigeschaltet(st.modus())) {
					HeavensWrathConfig.modus = st.modus();
				}
			}, (g, bx, by, w, h, hover) -> {
				boolean frei = HeavensWrathConfig.kills >= st.kills();
				boolean gewaehlt = HeavensWrathConfig.modus.equals(st.modus());
				rahmen(g, bx, by, w, h, !frei ? KARTE_GESPERRT : gewaehlt ? KARTE_GEWAEHLT : hover ? KARTE_HOVER : KARTE,
						gewaehlt ? GRUEN : frei ? GOLD : 0xFF553333);
				HeavensWrathGfx.item(g, new ItemStack(frei ? st.icon() : Items.BARRIER), bx + w / 2 - 8, by + 4);
				mitte(g, kuerze(st.name(), w - 4), bx, by + 23, w, 10, frei ? WEISS : GRAU);
				mitte(g, "ab " + st.kills() + " Kills", bx, by + 33, w, 10, frei ? GOLD : DUNKEL);
				mitte(g, gewaehlt ? "Aktiv" : frei ? "Frei" : "Gesperrt", bx, by + 43, w, 10,
						gewaehlt ? GRUEN : frei ? GRUEN : ROT);
			});
		}
	}

	private void karte(int x, int y, int w, int h, Runnable klick, Zeichner zeichner) {
		Button knopf = Button.builder(Component.empty(), b -> {
			klick.run();
			HeavensWrathConfig.speichern();
		}).bounds(x, y, w, h).build();
		this.addRenderableWidget(knopf);
		karten.add(new Karte(knopf, x, y, w, h, zeichner));
	}

	/** Schaltet den Skin des Assist-Spielers weiter: ich, dann alle Spieler auf dem Server. */
	private void naechsterSkin() {
		List<String> liste = new ArrayList<>();
		liste.add("");
		liste.addAll(HeavensWrathConfig.onlineNamen());
		int i = liste.indexOf(HeavensWrathConfig.assistSkin);
		HeavensWrathConfig.assistSkin = liste.get((i + 1) % liste.size());
	}

	// ------------------------------------------------------------------
	// Zeichnen
	// ------------------------------------------------------------------

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		HeavensWrathGfx.flaeche(g, links - 2, oben - 2, links + B + 2, oben + H + 2, GOLD);
		HeavensWrathGfx.flaeche(g, links, oben, links + B, oben + H, 0xF0141420);
		HeavensWrathGfx.flaeche(g, links, oben, links + B, oben + 18, 0xFF2A1B4D);
		HeavensWrathGfx.flaeche(g, links, oben + 18, links + B, oben + 19, GOLD);

		String titel = "HEAVEN'S WRATH";
		HeavensWrathGfx.text(g, this.font, titel, links + (B - this.font.width(titel)) / 2, oben + 5, GOLD);
		String kills = "Kills: " + HeavensWrathConfig.kills;
		HeavensWrathGfx.text(g, this.font, kills, links + B - 8 - this.font.width(kills), oben + 5, WEISS);
		HeavensWrathGfx.text(g, this.font, "Profil", links + 10, oben + 26, GRAU);
		HeavensWrathGfx.text(g, this.font, kuerze(HeavensWrathConfig.profilKurz(HeavensWrathConfig.profil), 140),
				links + 210, oben + 26, DUNKEL);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		for (Karte k : karten) {
			k.zeichner().zeichne(g, k.x(), k.y(), k.w(), k.h(), k.knopf().isHovered());
		}
		if (seite == 3) {
			zeichneFortschritt(g);
		}
	}

	/** Fortschrittsbalken bis zur nächsten Belohnung. */
	private void zeichneFortschritt(GuiGraphicsExtractor g) {
		int x = links + 10;
		int y = oben + 70;
		int w = B - 20;
		HeavensWrathPass.Stufe next = HeavensWrathPass.naechste();
		int von = 0;
		int bis = HeavensWrathPass.STUFEN.get(HeavensWrathPass.STUFEN.size() - 1).kills();
		if (next != null) {
			bis = next.kills();
			von = Math.max(0, bis - HeavensWrathPass.KILLS_PRO_STUFE);
		}
		double anteil = next == null ? 1.0 : Math.min(1.0, (double) (HeavensWrathConfig.kills - von) / (bis - von));
		HeavensWrathGfx.flaeche(g, x - 1, y - 1, x + w + 1, y + 13, DUNKEL);
		HeavensWrathGfx.flaeche(g, x, y, x + w, y + 12, 0xFF101018);
		HeavensWrathGfx.flaeche(g, x, y, x + (int) (w * anteil), y + 12, 0xFFB8860B);
		String text = next == null ? "Alle Effekte freigeschaltet!"
				: HeavensWrathConfig.kills + " / " + bis + " Kills bis \"" + next.name() + "\"";
		HeavensWrathGfx.text(g, this.font, text, x + (w - this.font.width(text)) / 2, y + 2, WEISS);
	}

	// ------------------------------------------------------------------
	// Kleine Helfer
	// ------------------------------------------------------------------

	private static void rahmen(GuiGraphicsExtractor g, int x, int y, int w, int h, int fuellung, int rand) {
		HeavensWrathGfx.flaeche(g, x, y, x + w, y + h, rand);
		HeavensWrathGfx.flaeche(g, x + 1, y + 1, x + w - 1, y + h - 1, fuellung);
	}

	/** Text waagerecht und senkrecht mittig im Feld. */
	private void mitte(GuiGraphicsExtractor g, String text, int x, int y, int w, int h, int farbe) {
		HeavensWrathGfx.text(g, this.font, text, x + (w - this.font.width(text)) / 2, y + (h - 8) / 2 + 1, farbe);
	}

	private String kuerze(String text, int max) {
		if (this.font.width(text) <= max) {
			return text;
		}
		String s = text;
		while (s.length() > 1 && this.font.width(s + "..") > max) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "..";
	}
}
