package com.hidarinovel.service;

/**
 * Callback invoked after each chapter is fetched during a download operation.
 *
 * @param current  1-based index of the chapter just processed
 * @param total    total number of chapters being downloaded
 * @param label    short label for the chapter (e.g. its title)
 */
@FunctionalInterface
public interface ProgressCallback {
    void update(int current, int total, String label);
}
