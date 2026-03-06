package com.hidarinovel.model;

import java.util.List;

/**
 * A volume/book within a novel, containing an ordered list of chapters.
 */
public record Volume(int number, String title, List<Chapter> chapters) {

    /** Global number of the first chapter in this volume. */
    public int firstGlobal() {
        return chapters.isEmpty() ? 0 : chapters.get(0).globalNumber();
    }

    /** Global number of the last chapter in this volume. */
    public int lastGlobal() {
        return chapters.isEmpty() ? 0 : chapters.get(chapters.size() - 1).globalNumber();
    }
}
