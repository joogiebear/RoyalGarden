package com.mystipixel.royalgarden.garden;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes the per-player YAML files on one background thread, so a save on the main thread costs only the
 * {@code saveToString} snapshot. Writes to one file are coalesced (only the newest queued text is written) and
 * never land out of order. Reads through {@link #load} and {@link #exists} see text that is queued but not yet
 * on disk.
 */
public final class YamlWriter {

    private record Job(String contents, Runnable onFailure) {
    }

    private static final Runnable NOTHING = () -> {
    };

    private final Logger logger;
    private final ExecutorService thread;
    // the newest text per file that the writer thread has not picked up yet
    private final Map<Path, Job> queued = new ConcurrentHashMap<>();
    // the text the writer thread is writing right now
    private final Map<Path, String> writing = new ConcurrentHashMap<>();
    // held around every file write, so a synchronous write can't interleave with a queued one
    private final Object lock = new Object();

    public YamlWriter(Logger logger) {
        this.logger = logger;
        this.thread = Executors.newSingleThreadExecutor(task -> {
            Thread t = new Thread(task, "RoyalGarden-Writer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Queue the text for the file and return at once. */
    public void write(Path target, String contents) {
        write(target, contents, NOTHING);
    }

    /** As {@link #write(Path, String)}; onFailure runs on the writer thread if this text could not be written. */
    public void write(Path target, String contents, Runnable onFailure) {
        if (queued.put(target, new Job(contents, onFailure)) == null) {
            try {
                thread.execute(() -> drain(target));
            } catch (RejectedExecutionException closed) {
                drain(target);                       // after close: write here
            }
        }
    }

    /**
     * Write the text now, for a caller that must know it is on disk before it moves money or items. Waits for a
     * write already running; an older queued text of the file is dropped.
     */
    public void writeNow(Path target, String contents) throws IOException {
        synchronized (lock) {
            Job older = queued.remove(target);
            try {
                GardenStore.writeAtomically(target, contents);
            } catch (IOException e) {
                if (older != null) {
                    queued.putIfAbsent(target, older);   // its drain is still scheduled
                }
                throw e;
            }
        }
    }

    /** Write anything queued for the file now. For a caller about to move the file. */
    public void flush(Path target) {
        drain(target);
    }

    /** The text queued or being written for the file, or null when the file on disk is current. */
    public String pending(Path target) {
        Job job = queued.get(target);
        return job != null ? job.contents() : writing.get(target);
    }

    public boolean exists(File file) {
        return pending(file.toPath()) != null || file.exists();
    }

    /** Load the file, or the text queued for it. */
    public void load(YamlConfiguration yaml, File file) throws IOException, InvalidConfigurationException {
        String contents = pending(file.toPath());
        if (contents != null) {
            yaml.loadFromString(contents);
        } else {
            yaml.load(file);
        }
    }

    private void drain(Path target) {
        synchronized (lock) {
            Job job;
            do {
                job = queued.get(target);
                if (job == null) {
                    return;
                }
                writing.put(target, job.contents());
            } while (!queued.remove(target, job));      // replaced meanwhile: take the newer text
            try {
                GardenStore.writeAtomically(target, job.contents());
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Could not save " + target, e);
                job.onFailure().run();
            } finally {
                writing.remove(target, job.contents());
            }
        }
    }

    /** Write everything still queued and stop the thread. Plugin disable, after the last save. */
    public void close() {
        thread.shutdown();
        try {
            if (!thread.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.severe("Garden saves did not finish within 30 s of shutdown; writing the rest here.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Path target : List.copyOf(queued.keySet())) {
            drain(target);
        }
    }
}
