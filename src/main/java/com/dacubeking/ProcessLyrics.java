package com.dacubeking;

import com.dacubeking.json.Lyrics;
import com.theokanning.openai.audio.TranscriptionResult;
import com.theokanning.openai.audio.Words;
import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ProcessLyrics {
    private static final DiffMatchPatch dmp = new DiffMatchPatch();

    static {
        dmp.matchDistance = 100;
    }


    private static final HashSet<String> commonWords = new HashSet<>(10000);
    static {
        try {
            commonWords.addAll(Files.readAllLines(Path.of("google-10000-english.txt")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


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
                    Lyrics processedLyrics;
                    try {
                         processedLyrics = processLyrics(videoId, lyrics);
                    } catch (Exception e){
                        e.printStackTrace();
                        return Optional.empty();
                    }

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
        if (!videoId.matches("^[a-zA-Z0-9_-]{11}$")) {
            return new Lyrics();
        }

        String audioPath = "./data/music_downloads/" + videoId + ".webm";

        if (!Files.exists(Path.of("/data/music_downloads/" + videoId + ".webm"))) {
            // Download music
            String[] command = new String[]{"yt-dlp", "-f", "bestaudio", "-o", audioPath, videoId};
            try {
                Process process = Runtime.getRuntime().exec(command);
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                return optionalLyrics.orElse(new Lyrics());
            }
        }

        ArrayList<String> originalWords = new ArrayList<>();
        StringBuilder prompt = new StringBuilder();
        if (optionalLyrics.isPresent()) {
            Lyrics originalLyrics = optionalLyrics.get();
            for (Lyrics.TimedLyric originalLyric : originalLyrics.lyrics) {
                String[] words = originalLyric.words().split(" ");
                if (words.length > 0){
                    var lastWord = words[words.length - 1];
                    if (!lastWord.endsWith("\n")) {
                        lastWord = lastWord + "\n";
                        words[words.length - 1] = lastWord;
                    }
                }
                originalWords.addAll(Arrays.asList(words));
            }

            HashMap<String, Integer> uncommonWordCounts = new HashMap<>();
            originalWords.stream().map(String::toLowerCase).filter(word -> !commonWords.contains(word))
                    .forEach(word -> uncommonWordCounts.merge(word, 1, Integer::sum));

            var sortedUncommonWords = uncommonWordCounts.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).toList();

            // to be safe we can only add about 100 words
            for (int i = 0; i < 100; i++) {
                prompt.append(originalWords.get(i)).append(" ");
            }

        }


        File musicFile = new File(audioPath);

        TranscriptionResult transcriptionResult = OpenAI.getTranscription(musicFile, videoId, prompt.toString());

        Lyrics lyrics = new Lyrics();
        lyrics.lyrics = new ArrayList<>();
        if (optionalLyrics.isEmpty()) {
            StringBuilder tempWords = new StringBuilder();
            long[] tempTimingStartsMs = new long[5];
            long[] tempTimingEndsMs = new long[5];
            long startTime = 0;
            int index = 0;

            for (var word : transcriptionResult.getWords()) {
                if (index == 0) {
                    startTime = (long) (word.getStart() * 1000);
                }
                tempWords.append(word.getWord()).append(" ");
                tempTimingStartsMs[index] = (long) (word.getStart() * 1000) - startTime;
                tempTimingEndsMs[index] = (long) (word.getEnd() * 1000) - startTime;
                index++;

                if (index == 5) {
                    lyrics.lyrics.add(new Lyrics.TimedLyric(
                            startTime,
                            tempTimingEndsMs[index - 1],
                            tempWords.toString(),
                            tempTimingStartsMs,
                            tempTimingEndsMs
                    ));
                    index = 0;
                    tempTimingStartsMs = new long[5];
                    tempTimingEndsMs = new long[5];
                    tempWords = new StringBuilder();
                }
            }
            if (index > 0) {
                lyrics.lyrics.add(new Lyrics.TimedLyric(
                        startTime,
                        tempTimingEndsMs[index - 1],
                        tempWords.toString(),
                        tempTimingStartsMs,
                        tempTimingEndsMs
                ));
            }
        } else {
            List<Words> transcribedWords = transcriptionResult.getWords();

            int originalWordsIndex = 0;
            int transcribedWordsIndex = 0;
            boolean hasTrailingSpace = false;

            TempLyric tempLyric = new TempLyric();

            LinkedList<DiffMatchPatch.Diff> diffsLinkedList = dmp.diffMain(String.join(" ", originalWords).toLowerCase().replace("\n", ""),
                    transcribedWords.stream().map(Words::getWord).collect(Collectors.joining(" ")).toLowerCase()
            );

            List<DiffMatchPatch.Diff> diffs = diffsLinkedList.stream().toList();
            System.out.println(diffs);

            Map<DiffMatchPatch.Operation, DiffMatchPatch.Operation> oppositeOperations = Map.of(
                    DiffMatchPatch.Operation.INSERT, DiffMatchPatch.Operation.DELETE,
                    DiffMatchPatch.Operation.DELETE, DiffMatchPatch.Operation.INSERT
            );

            System.out.println("Starting Matching");
            System.out.printf("Original Len: %d, Transcribed Len: %d", originalWords.size(), transcribedWords.size());

            for (int i = 0; i < diffs.size(); i++) {
                DiffMatchPatch.Diff diff = diffs.get(i);
                if (diff.operation == DiffMatchPatch.Operation.EQUAL) {
                    var wordsToAdd = countSpaces(diff.text);
                    for (int j = 0; j < wordsToAdd; j++) {
                        tempLyric.addNewWord(originalWords.get(originalWordsIndex), transcribedWords.get(transcribedWordsIndex), lyrics);
                        System.out.printf("Matched: %s -> %s, index: %d, %d%n", originalWords.get(originalWordsIndex), transcribedWords.get(transcribedWordsIndex), originalWordsIndex, transcribedWordsIndex);
                        originalWordsIndex++;
                        transcribedWordsIndex++;
                    }
                } else {
                    boolean isNextItem = i + 1 < diffs.size();
                    boolean isNextOperationOpposite = false;
                    long wordsInThisDiff = 0;
                    long wordsInNextDiff = 0;
                    if (isNextItem) {
                         isNextOperationOpposite = diffs.get(i + 1).operation == oppositeOperations.get(diff.operation);
                         wordsInThisDiff = countSpaces(diff.text);
                         wordsInNextDiff = countSpaces(diffs.get(i + 1).text);
                    }

                    if (isNextItem && isNextOperationOpposite && (wordsInThisDiff == wordsInNextDiff)) {
                        // Some words were switched around
                        var wordsToAdd = countSpaces(diff.text);
                        for (int j = 0; j < wordsToAdd; j++) {
                            tempLyric.addNewWord(originalWords.get(originalWordsIndex), transcribedWords.get(transcribedWordsIndex), lyrics);
                            System.out.printf("Mismatched: %s -> %s, index: %d, %d%n", originalWords.get(originalWordsIndex), transcribedWords.get(transcribedWordsIndex), originalWordsIndex, transcribedWordsIndex);
                            originalWordsIndex++;
                            transcribedWordsIndex++;
                        }
                    } else if (diff.operation == DiffMatchPatch.Operation.INSERT) {
                        var wordsToAdd = countSpaces(diff.text);;
                        // Drop these words from the transcript
                        for (int j = 0; j < wordsToAdd; j++) {
                            System.out.printf("Extra Word: %s, Diff: %s index: %d, %d%n", transcribedWords.get(transcribedWordsIndex), diff.text, originalWordsIndex, transcribedWordsIndex);
                            transcribedWordsIndex++;
                        }
                    } else if (diff.operation == DiffMatchPatch.Operation.DELETE) {
                        var wordsToAdd = countSpaces(diff.text);
                        // We're missing these words from the transcript

                        double lastTranscribedWordEnd = 0;
                        if (transcribedWordsIndex > 0) {
                            lastTranscribedWordEnd = transcribedWords.get(transcribedWordsIndex - 1).getEnd();
                        }
                        double nextTranscribedWordBegin = lastTranscribedWordEnd;
                        if (transcribedWordsIndex < transcribedWords.size()) {
                            nextTranscribedWordBegin = transcribedWords.get(transcribedWordsIndex).getStart();
                        }

                        double eachWordTime = (nextTranscribedWordBegin - lastTranscribedWordEnd) / (double) (wordsToAdd + 1);
                        System.out.printf("Word Each Time %f, lastEnd %f, nextStart %f, words to add %d %n",eachWordTime, lastTranscribedWordEnd, nextTranscribedWordBegin, wordsToAdd);

                        for (int j = 0; j < wordsToAdd; j++) {
                            var start = lastTranscribedWordEnd + eachWordTime * (j + 1);
                            var end = lastTranscribedWordEnd + eachWordTime * (j + 2);
                            System.out.printf("Missing Word: %s, Diff: %s, Placing @ %s index: %d, %d%n", originalWords.get(originalWordsIndex), diff.text, start, originalWordsIndex, transcribedWordsIndex);
                            tempLyric.addNewWord(originalWords.get(originalWordsIndex), start, end, lyrics);
                            originalWordsIndex++;
                        }
                    }
                }
            }

            if (!tempLyric.tempWords.isEmpty()) {
                lyrics.lyrics.add(tempLyric.getTimedLyric());
            }
        }


        lyrics.error = "null";
        lyrics.isRtlLanguage = false;
        lyrics.language = optionalLyrics.isPresent() ? optionalLyrics.get().language : transcriptionResult.getLanguage();
        lyrics.trackId = optionalLyrics.isPresent() ? optionalLyrics.get().trackId : videoId;

        return lyrics;
    }

    private static class TempLyric {
        StringBuilder tempWords = new StringBuilder();
        ArrayList<Long> tempTimingStartsMs = new ArrayList<>();
        ArrayList<Long> tempTimingEndsMs = new ArrayList<>();
        long startTime = 0;

        private void addNewWord(String word, Words matchingTranscribedWord, Lyrics lyrics) {
            addNewWord(word, matchingTranscribedWord.getStart(), matchingTranscribedWord.getEnd(), lyrics);
        }

        private void addNewWord(String word, double startTimeS, double endTimeS,  Lyrics lyrics) {
            if (tempWords.isEmpty()) {
                startTime = (long) (startTimeS * 1000L);
            }

            tempWords.append(word);

            tempTimingStartsMs.add((long) (startTimeS * 1000L) - startTime);
            tempTimingEndsMs.add((long) (endTimeS * 1000L) - startTime);
            if (word.endsWith("\n")) {
                lyrics.lyrics.add(getTimedLyric());
                tempWords = new StringBuilder();
                tempTimingStartsMs = new ArrayList<>();
                tempTimingEndsMs = new ArrayList<>();
                startTime = 0;
            } else {
                tempWords.append(" ");
            }
        }

        private Lyrics.TimedLyric getTimedLyric() {
            if (tempWords.charAt(tempWords.length() - 1) == '\n') {
                tempWords.deleteCharAt(tempWords.length() - 1);
            }
            return new Lyrics.TimedLyric(
                    startTime,
                    tempTimingEndsMs.getLast(),
                    tempWords.toString(),
                    tempTimingStartsMs.stream().mapToLong(l -> l).toArray(),
                    tempTimingEndsMs.stream().mapToLong(l -> l).toArray()
            );
        }
    }

    private static long countSpaces(String string) {
        return string.chars().filter(ch -> ch == ' ').count();
    }

    private static long countSpacesExceptLast(String string) {
        long count = countSpaces(string);
        if (endsWithSpace(string)) {
            count -= 1;
        }
        return count;
    }

    private static boolean endsWithSpace(String string) {
        return string.charAt(string.length() - 1) == ' ';
    }
}
