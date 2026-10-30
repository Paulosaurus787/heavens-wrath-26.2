package de.heavenswrath;

import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/** /killeffect – Menü im Chat mit anklickbaren Knöpfen. */
public final class HeavensWrathBefehl {

	private HeavensWrathBefehl() {
	}

	public static void registrieren(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		LiteralArgumentBuilder<FabricClientCommandSource> wurzel = ClientCommands.literal("killeffect")
				.executes(ctx -> {
					HeavensWrathScreen.angefordert = 0;
					return 1;
				});

		wurzel.then(ClientCommands.literal("an").executes(ctx -> {
			HeavensWrathConfig.aktiv = true;
			HeavensWrathConfig.speichern();
			menue(ctx.getSource());
			return 1;
		}));
		wurzel.then(ClientCommands.literal("aus").executes(ctx -> {
			HeavensWrathConfig.aktiv = false;
			HeavensWrathConfig.speichern();
			menue(ctx.getSource());
			return 1;
		}));

		LiteralArgumentBuilder<FabricClientCommandSource> item = ClientCommands.literal("item");
		item.then(ClientCommands.literal("waffe").executes(ctx -> {
			setzeItem("waffe");
			menue(ctx.getSource());
			return 1;
		}));
		for (String name : HeavensWrathConfig.ITEMS.keySet()) {
			item.then(ClientCommands.literal(name).executes(ctx -> {
				setzeItem(name);
				menue(ctx.getSource());
				return 1;
			}));
		}
		wurzel.then(item);

		LiteralArgumentBuilder<FabricClientCommandSource> koerper = ClientCommands.literal("koerper");
		koerper.then(ClientCommands.literal("stehen").executes(ctx -> {
			HeavensWrathConfig.koerperAufloesen = false;
			HeavensWrathConfig.speichern();
			menue(ctx.getSource());
			return 1;
		}));
		koerper.then(ClientCommands.literal("aufloesen").executes(ctx -> {
			HeavensWrathConfig.koerperAufloesen = true;
			HeavensWrathConfig.speichern();
			menue(ctx.getSource());
			return 1;
		}));
		wurzel.then(koerper);

		LiteralArgumentBuilder<FabricClientCommandSource> modus = ClientCommands.literal("modus");
		for (String m : HeavensWrathConfig.MODI) {
			modus.then(ClientCommands.literal(m).executes(ctx -> {
				if (HeavensWrathPass.freigeschaltet(m)) {
					HeavensWrathConfig.modus = m;
					HeavensWrathConfig.speichern();
				} else {
					HeavensWrathPass.Stufe st = HeavensWrathPass.stufe(m);
					ctx.getSource().sendFeedback(Component.literal("Noch gesperrt: " + m + " gibt es ab "
							+ (st == null ? "?" : st.kills()) + " Kills (du hast " + HeavensWrathConfig.kills + ").")
							.withStyle(ChatFormatting.RED));
				}
				menue(ctx.getSource());
				return 1;
			}));
		}
		wurzel.then(modus);

		LiteralArgumentBuilder<FabricClientCommandSource> profil = ClientCommands.literal("profil");
		for (int i = 1; i <= HeavensWrathConfig.PROFILE; i++) {
			int nummer = i - 1;
			profil.then(ClientCommands.literal(String.valueOf(i)).executes(ctx -> {
				HeavensWrathConfig.waehleProfil(nummer);
				menue(ctx.getSource());
				return 1;
			}));
		}
		wurzel.then(profil);

		wurzel.then(ClientCommands.literal("pass").executes(ctx -> {
			HeavensWrathPass.Stufe next = HeavensWrathPass.naechste();
			ctx.getSource().sendFeedback(Component.literal("Battle Pass: " + HeavensWrathConfig.kills + " Kills, "
					+ HeavensWrathPass.freigeschalteteStufen() + "/" + HeavensWrathPass.STUFEN.size() + " Effekte frei."
					+ (next == null ? " Alles freigeschaltet!" : " Nächster: " + next.name() + " bei " + next.kills() + " Kills."))
					.withStyle(ChatFormatting.GOLD));
			return 1;
		}));

		dispatcher.register(wurzel);
		registriereSound(dispatcher);
	}

	/** /killsound – Menü zum Wechseln des Einschlag-Sounds (mit Probe-Abspielen). */
	private static void registriereSound(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		LiteralArgumentBuilder<FabricClientCommandSource> wurzel = ClientCommands.literal("killsound")
				.executes(ctx -> {
					HeavensWrathScreen.angefordert = 2;
					return 1;
				});
		for (String name : HeavensWrathConfig.soundNamen()) {
			wurzel.then(ClientCommands.literal(name).executes(ctx -> {
				HeavensWrathConfig.sound = name;
				HeavensWrathConfig.speichern();
				HeavensWrathConfig.vorschau();
				soundMenue(ctx.getSource());
				return 1;
			}));
		}
		dispatcher.register(wurzel);
	}

	private static void soundMenue(FabricClientCommandSource quelle) {
		quelle.sendFeedback(Component.literal("===== Heaven's Wrath: Sound =====").withStyle(ChatFormatting.GOLD));
		quelle.sendFeedback(zeile("Sound: ", HeavensWrathConfig.sound.replace('_', ' ')));
		MutableComponent knoepfe = Component.literal("  ");
		for (String name : HeavensWrathConfig.soundNamen()) {
			knoepfe.append(knopf(name.replace('_', ' '), "/killsound " + name, HeavensWrathConfig.sound.equals(name)));
		}
		quelle.sendFeedback(knoepfe);
		quelle.sendFeedback(Component.literal("Ein Klick spielt den Sound zur Probe ab.").withStyle(ChatFormatting.GRAY));
	}

	private static void setzeItem(String name) {
		HeavensWrathConfig.item = name;
		HeavensWrathConfig.speichern();
	}

	private static void menue(FabricClientCommandSource quelle) {
		quelle.sendFeedback(Component.literal("===== Heaven's Wrath =====  Profil " + (HeavensWrathConfig.profil + 1)
				+ " | Kills: " + HeavensWrathConfig.kills).withStyle(ChatFormatting.GOLD));

		quelle.sendFeedback(zeile("Effekt: ", HeavensWrathConfig.aktiv ? "AN" : "AUS")
				.append(knopf("an", "/killeffect an", HeavensWrathConfig.aktiv))
				.append(knopf("aus", "/killeffect aus", !HeavensWrathConfig.aktiv)));

		quelle.sendFeedback(zeile("Körper: ", HeavensWrathConfig.koerperAufloesen ? "löst sich auf" : "bleibt stehen")
				.append(knopf("stehen", "/killeffect koerper stehen", !HeavensWrathConfig.koerperAufloesen))
				.append(knopf("auflösen", "/killeffect koerper aufloesen", HeavensWrathConfig.koerperAufloesen)));

		quelle.sendFeedback(zeile("Item: ", HeavensWrathConfig.item.equals("waffe")
				? "deine Waffe" : HeavensWrathConfig.item.replace('_', ' ')));
		MutableComponent items = Component.literal("  ");
		items.append(knopf("deine Waffe", "/killeffect item waffe", HeavensWrathConfig.item.equals("waffe")));
		for (Map.Entry<String, ?> e : HeavensWrathConfig.ITEMS.entrySet()) {
			String name = e.getKey();
			items.append(knopf(name.replace('_', ' '), "/killeffect item " + name, HeavensWrathConfig.item.equals(name)));
		}
		quelle.sendFeedback(items);

		MutableComponent modi = zeile("Kill-Effekt: ", HeavensWrathConfig.modus);
		for (String m : HeavensWrathConfig.MODI) {
			modi.append(knopf(m, "/killeffect modus " + m, HeavensWrathConfig.modus.equals(m)));
		}
		quelle.sendFeedback(modi);

	}

	private static MutableComponent zeile(String titel, String wert) {
		return Component.literal(titel).withStyle(ChatFormatting.GRAY)
				.append(Component.literal(wert).withStyle(ChatFormatting.WHITE));
	}

	private static MutableComponent knopf(String text, String befehl, boolean gewaehlt) {
		return Component.literal(" [" + text + "]").withStyle(stil -> stil
				.withColor(gewaehlt ? ChatFormatting.GREEN : ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.RunCommand(befehl))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(befehl))));
	}
}
