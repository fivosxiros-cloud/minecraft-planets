package me.foivos.planets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The plugin's own resource pack: the vanilla font, plus four blank glyphs one,
 * two, three and four pixels wide.
 *
 * <p>It exists for one job. The only blank glyph the client has of its own is a
 * space, and a space is four pixels wide, so a column of {@code |} separators
 * can only be padded in steps of four and ends up a pixel or two out whenever
 * the labels above it are of awkward widths. With these glyphs the sidebar pads
 * to the exact pixel, which is what {@code sidebar.fine-padding} asks for.
 *
 * <p>It is written out beside {@code config.yml} only when that setting is on,
 * so nothing appears on a server that has not asked for it. A server owner can
 * host the file and point {@code resource-pack.url} at it (the SHA-1 to put in
 * {@code resource-pack.sha1} is printed to the console), or unzip it into a
 * client's {@code resourcepacks} folder to try it out by hand.
 */
final class SidebarPack {

    /** The name of the pack, in the plugin's {@code resourcepack} folder. */
    private static final String PACK_NAME = "planetarium-pack.zip";

    /** What goes into it: the manifest, then the font the sidebar pads with. */
    private static final List<String> ENTRIES = List.of(
            "pack.mcmeta",
            "assets/planetarium/font/fine_space.json");

    private SidebarPack() {
    }

    /**
     * Writes the pack beside {@code config.yml}, replacing any older copy.
     *
     * @return the file, or null when it could not be written
     */
    static File write(Planets plugin) {
        File folder = new File(plugin.getDataFolder(), "resourcepack");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create " + folder.getPath());
            return null;
        }
        File pack = new File(folder, PACK_NAME);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(pack))) {
            for (String entry : ENTRIES) {
                try (InputStream in = plugin.getResource("resourcepack/" + entry)) {
                    if (in == null) {
                        plugin.getLogger().warning("resourcepack/" + entry
                                + " is missing from the plugin jar");
                        return null;
                    }
                    zip.putNextEntry(new ZipEntry(entry));
                    in.transferTo(zip);
                    zip.closeEntry();
                }
            }
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not write " + PACK_NAME + ": " + failure.getMessage());
            return null;
        }
        return pack;
    }

    /** The pack's SHA-1, which is what {@code resource-pack.sha1} wants. */
    static String sha1(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(40);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16))
                        .append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException failure) {
            return "";
        }
    }
}
