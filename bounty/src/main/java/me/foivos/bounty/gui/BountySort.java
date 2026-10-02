package me.foivos.bounty.gui;

/**
 * How the board orders the players on it. The player's own head sits in its own
 * slot either way, so this only ever moves the rest.
 */
public enum BountySort {

    /** Biggest bounty first: the board reads like a most-wanted list. */
    HIGHEST_FIRST(true, "gui.sort-highest"),

    /** Smallest first, for finding the cheapest bounty to go after. */
    LOWEST_FIRST(false, "gui.sort-lowest");

    private final boolean highestFirst;
    private final String messageKey;

    BountySort(boolean highestFirst, String messageKey) {
        this.highestFirst = highestFirst;
        this.messageKey = messageKey;
    }

    /** Whether the biggest bounty comes first. */
    public boolean highestFirst() {
        return highestFirst;
    }

    /** The other mode: what a click on the sorting item switches to. */
    public BountySort next() {
        return this == HIGHEST_FIRST ? LOWEST_FIRST : HIGHEST_FIRST;
    }

    /** Where the wording for this mode lives in config.yml. */
    public String messageKey() {
        return messageKey;
    }
}
