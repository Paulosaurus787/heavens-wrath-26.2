package de.heavenswrath;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/** Dünne Schicht über dem Zeichnen im GUI (heißt in 26.x anders als in 1.21.11). */
final class HeavensWrathGfx {

	private HeavensWrathGfx() {
	}

	static void flaeche(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int farbe) {
		g.fill(x1, y1, x2, y2, farbe);
	}

	static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int farbe) {
		g.text(font, text, x, y, farbe, true);
	}

	static void item(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
		g.item(stack, x, y);
	}
}
