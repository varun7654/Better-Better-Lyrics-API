package com.dacubeking;

import com.dacubeking.json.Lyrics;

import javax.swing.text.html.Option;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

public class ProcessLyrics {
    private static final Hashtable<String, ReentrantReadWriteLock> lyricProcessingLocks = new Hashtable<>();

    public static Optional<String> getLyrics(String videoId, Supplier<Optional<Lyrics>> getLyrics) {
        ReadWriteLock lock = lyricProcessingLocks.computeIfAbsent(videoId, k -> new ReentrantReadWriteLock());
        lock.readLock().lock();
        var expectedPath = Path.of("data", videoId + "-lyrics.json");
        if (Files.exists(expectedPath)) {
            try {
                lock.readLock().unlock();
                return Optional.of(Files.readString(expectedPath));
            } catch (IOException e) {
                lock.readLock().unlock();
                return Optional.empty();
            }
        } else {
            System.out.println("No lyrics found for video: " + videoId);
            lock.readLock().unlock();
            lock.writeLock().lock();
            try {
                // Check that the file doesn't exist now
                if (Files.exists(expectedPath)) {
                    try {
                        return Optional.of(Files.readString(expectedPath));
                    } catch (IOException e) {
                        return Optional.empty();
                    }
                } else {
                    var lyrics = getLyrics.get();
                    var processedLyrics = processLyrics(videoId, lyrics);
                    String processedLyricsString = Main.mapper.writeValueAsString(processedLyrics);
                    Files.createFile(expectedPath);
                    Files.write(expectedPath, processedLyricsString.getBytes());
                    return Optional.of(processedLyricsString);
                }

            } catch (IOException e) {
                e.printStackTrace();
                return Optional.empty();
            } finally {
                lock.writeLock().unlock();
            }
        }
    }


    private static Lyrics processLyrics(String videoId, Optional<Lyrics> optionalLyrics) {

        return optionalLyrics.orElseGet(Lyrics::new);
    }
}
