package me.foivos.planets.casino;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * The casino's one voice: every click, tick, win and loss sounds the same
 * wherever it comes from.
 *
 * <p>It exists so a new game does not have to invent its own feedback to feel
 * finished — a game that calls these reads like the rest of the hub for free.
 * The sounds are all vanilla, so nothing here needs a resource pack.
 */
public final class CasinoFeedback {

    private CasinoFeedback() {
    }

    /** A button press. */
    public static void click(Player player) {
        play(player, Sound.UI_BUTTON_CLICK, 0.7f, 1.4f);
    }

    /** Going back a screen. */
    public static void back(Player player) {
        play(player, Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
    }

    /** A screen opening. */
    public static void open(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.5f);
    }

    /** One step of an animation, e.g. a reel or a rolling die. */
    public static void tick(Player player, int step) {
        play(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f,
                1.2f + Math.min(8, step) * 0.08f);
    }

    /** A round that went the player's way. */
    public static void win(Player player) {
        play(player, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
        burst(player, Particle.END_ROD, 12);
    }

    /** A round that did not. */
    public static void lose(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
    }

    /** A prize being revealed, louder the better the tier. */
    public static void prize(Player player, CasinoRarity rarity) {
        CasinoRarity tier = rarity == null ? CasinoRarity.COMMON : rarity;
        switch (tier) {
            case LEGENDARY, EPIC -> {
                play(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
                play(player, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.9f, 1.2f);
                burst(player, Particle.GLOW, 24);
                burst(player, Particle.END_ROD, 18);
            }
            case RARE -> {
                play(player, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1.3f);
                burst(player, Particle.GLOW, 14);
            }
            default -> {
                play(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
                burst(player, Particle.ENCHANT, 8);
            }
        }
    }

    /** An achievement unlocking. */
    public static void achievement(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, 1.2f);
        play(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.7f);
        burst(player, Particle.GLOW, 16);
    }

    /** A click that could not be obeyed, and why is said in chat. */
    public static void deny(Player player) {
        play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
    }

    private static void play(Player player, Sound sound, float volume, float pitch) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /** A ring of particles around the player, so a win is felt as well as heard. */
    private static void burst(Player player, Particle particle, int count) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Location at = player.getLocation();
        player.getWorld().spawnParticle(particle, at.getX(), at.getY() + 1.0, at.getZ(),
                count, 0.5, 0.7, 0.5, 0.02);
    }
}
