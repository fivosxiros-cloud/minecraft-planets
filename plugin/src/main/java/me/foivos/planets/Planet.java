package me.foivos.planets;

import org.bukkit.Material;

/**
 * A destination shown in the planet menu. {@code worldName} is the Bukkit world
 * the player gets dropped into (at a random safe spot inside it).
 */
public record Planet(String name, Material icon, String worldName) {
}