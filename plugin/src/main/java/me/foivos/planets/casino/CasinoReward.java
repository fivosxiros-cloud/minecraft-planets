package me.foivos.planets.casino;

import org.bukkit.Material;

import java.util.List;

/**
 * One prize a casino game can hand out.
 *
 * <p>A reward is deliberately <b>only a record of itself</b>: collecting one
 * writes a name and a tally into {@code casino.yml} and shows it in the prize
 * cabinet. Nothing is ever put into a player's inventory, so a prize can never
 * be traded, sold or swapped for anything — which is what keeps the whole
 * casino cosmetic, and keeps a "win" from being worth anything outside the
 * game it was won in.
 *
 * @param id     the stable key used in config.yml and in a player's record
 * @param name   what the prize is called in chat and in the cabinet
 * @param rarity the tier it is announced under
 * @param icon   the item shown for it in the cabinet
 * @param lore   extra lines describing it (may be empty)
 * @param weight how likely it is relative to the other prizes; 0 means it can
 *               still be listed and collected, but never rolls
 */
public record CasinoReward(String id, String name, CasinoRarity rarity, Material icon,
                           List<String> lore, int weight) {

    public CasinoReward {
        lore = lore == null ? List.of() : List.copyOf(lore);
        weight = Math.max(0, weight);
    }

    /** Whether this prize can come out of a roll. */
    public boolean rollable() {
        return weight > 0;
    }

    /** The prize's name, coloured by its tier. */
    public net.kyori.adventure.text.Component displayName() {
        return net.kyori.adventure.text.Component.text(name)
                .color(rarity.colour())
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }
}
