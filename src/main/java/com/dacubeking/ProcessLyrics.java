package com.dacubeking;

import com.dacubeking.json.Lyrics;
import com.theokanning.openai.audio.TranscriptionResult;
import com.theokanning.openai.audio.Words;
import jdk.jshell.spi.ExecutionControl;
import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ProcessLyrics {
    private static final DiffMatchPatch dmp = new DiffMatchPatch();
    private static final HashSet<String> commonWords = new HashSet<>(10000);
    private static final Hashtable<String, ReentrantReadWriteLock> lyricProcessingLocks = new Hashtable<>();

    static {
        dmp.matchDistance = 100;
    }

    static {
        try {
            commonWords.addAll(Files.readAllLines(Path.of("google-10000-english.txt")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

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
                    } catch (Exception e) {
                        e.printStackTrace();
                        return Optional.empty();
                    }

                    if (processedLyrics == null) {
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


    private static Lyrics processLyrics(String videoId, Optional<Lyrics> optionalLyrics) throws ExecutionException, InterruptedException {
        if (!videoId.matches("^[a-zA-Z0-9_-]{11}$")) {
            return new Lyrics();
        }

        String audioPath = "./data/music_downloads/" + videoId + ".webm";

        if (!Files.exists(Path.of("'/data/music_downloads/" + videoId + ".webm'"))) {
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



        Lyrics lyrics = new Lyrics();

        if (optionalLyrics.isEmpty()) {
            File musicFile = new File(audioPath);

            TranscriptionResult transcriptionResult = OpenAI.getTranscription(musicFile, videoId, "");


            var endPunctuation = ".!?)";
            StringBuilder tempWords = new StringBuilder();
            long[] tempTimingStartsMs = new long[20];
            long[] tempTimingEndsMs = new long[20];
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

                if (index == 20 || endPunctuation.contains(tempWords.substring(tempWords.length() - 1))) {
                    lyrics.lyrics.add(new Lyrics.TimedLyric(
                            startTime,
                            tempTimingEndsMs[index - 1],
                            tempWords.toString(),
                            tempTimingStartsMs,
                            tempTimingEndsMs
                    ));
                    index = 0;
                    tempTimingStartsMs = new long[20];
                    tempTimingEndsMs = new long[20];
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
            return lyrics;
        } else {

            new File("./data/music_downloads/" + videoId + "/partId.webm").mkdirs();

            Lyrics originalLyrics = optionalLyrics.get();
            ArrayList<CompletableFuture<Lyrics.TimedLyric>> lyricFutures = new ArrayList<>();
            for (int i = 0; i < originalLyrics.lyrics.size(); i++) {
                long nextLyricMs = 10000000000l;
                if (i + 1 < originalLyrics.lyrics.size()) {
                    nextLyricMs = originalLyrics.lyrics.get(i + 1).startTimeMs();
                }
                var future = processLyric(i, originalLyrics.lyrics.get(i), nextLyricMs, videoId);
                lyricFutures.add(future);
            }

            for (int i = 0; i < lyricFutures.size(); i++) {
                lyrics.lyrics.add(lyricFutures.get(i).get());
            }
        }


        lyrics.error = "null";
        lyrics.isRtlLanguage = false;
        lyrics.language = optionalLyrics.isPresent() ? optionalLyrics.get().language : "en";
        lyrics.trackId = optionalLyrics.isPresent() ? optionalLyrics.get().trackId : videoId;

        return lyrics;
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

    private static class TempLyric {
        StringBuilder tempWords = new StringBuilder();
        ArrayList<Long> tempTimingStartsMs = new ArrayList<>();
        ArrayList<Long> tempTimingEndsMs = new ArrayList<>();
        long startTime = 0;

        private void addNewWord(String word, Words matchingTranscribedWord, Lyrics lyrics, Lyrics originalLyrics) {
            addNewWord(word, matchingTranscribedWord.getStart(), matchingTranscribedWord.getEnd(), lyrics, originalLyrics);
        }

        private void addNewWord(String word, double startTimeS, double endTimeS, Lyrics lyrics, Lyrics originalLyrics) {
            if (tempWords.isEmpty()) {
                startTime = originalLyrics.lyrics.get(lyrics.lyrics.size()).startTimeMs();
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


    private static String cutAudio(String videoId, double start, double end, int partId) {
        // ffmpeg -ss 60.1 -i 0I0M5R8HleQ.webm -t 2.982 -c copy ./0I0M5R8HleQ/0I0M5R8HleQ.webm
        String audioPath = new File("data/music_downloads/" + videoId + ".webm").getAbsolutePath();
        String outPath = new File("data/music_downloads/" + videoId + "/" + partId + ".webm").getAbsolutePath();

        String[] command = new String[]{"ffmpeg", "-y", "-ss", "" + (start), "-i", audioPath, "-t", "" + (end-start), outPath};
        try {
            Process proc = Runtime.getRuntime().exec(command);
            proc.waitFor();
//            BufferedReader stdInput = new BufferedReader(new
//                    InputStreamReader(proc.getInputStream()));
//
//            BufferedReader stdError = new BufferedReader(new
//                    InputStreamReader(proc.getErrorStream()));
//
//            String s = null;
//            while ((s = stdInput.readLine()) != null) {
//                System.out.println(s);
//            }
//
//            while ((s = stdError.readLine()) != null) {
//                System.out.println(s);
//            }


        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return outPath;
    }

    private static final ExecutorService lyricProcessingPool = Executors.newVirtualThreadPerTaskExecutor();

    record WordPair(String prompted, String actual){}


    private static double CUT_START_OFFSET = 0.1;
    private static CompletableFuture<Lyrics.TimedLyric> processLyric(int lyricId, Lyrics.TimedLyric originalLyric, long nextLyricStartMs, String videoId) {
        CompletableFuture<Lyrics.TimedLyric> future = new CompletableFuture<>();
        lyricProcessingPool.submit(() -> {
            try {
                if (originalLyric.words().trim().isEmpty() || originalLyric.words().trim().equals("♪")) {
                    var numWords = originalLyric.words().split(" ").length;
                    long[] begins = new long[numWords];
                    long[] ends = new long[numWords];
                    for (int i = 0; i < numWords; i++) {
                        begins[i] = 0;
                        ends[i] = 0;
                    }
                    future.complete(new Lyrics.TimedLyric(originalLyric.startTimeMs(), (nextLyricStartMs - originalLyric.startTimeMs()), originalLyric.words(), begins, ends));
                }


                double start = (originalLyric.startTimeMs() / 1000d) - CUT_START_OFFSET;
                double end = (nextLyricStartMs / 1000d);
                String cutAudioFile = cutAudio(videoId, start, end, lyricId);
                //System.out.printf("Id: %d Start: %.3f, End:  %.3f, Duration:  %.3f, File: %s, Lyric: %s%n", lyricId, start, end, end-start, cutAudioFile, originalLyric.words());
                var musicFile = new File(cutAudioFile);
                TranscriptionResult transcriptionResult = OpenAI.getTranscription(musicFile, videoId + "_" + lyricId, originalLyric.words());

                System.out.println(transcriptionResult);

                long[] wordRelativeTimingsStart = new long[transcriptionResult.getWords().size()];
                long[] wordRelativeTimingsEnd = new long[transcriptionResult.getWords().size()];
                for (int i = 0; i < transcriptionResult.getWords().size(); i++) {
                    wordRelativeTimingsStart[i] = (long) ((transcriptionResult.getWords().get(i).getStart() + CUT_START_OFFSET) * 1000) ;
                    wordRelativeTimingsEnd[i] = (long) ((transcriptionResult.getWords().get(i).getEnd() + CUT_START_OFFSET) * 1000) ;
                }
                System.out.println(transcriptionResult.getWords());

                long duration = 0;
                if (wordRelativeTimingsEnd.length > 0) {
                    duration = wordRelativeTimingsEnd[transcriptionResult.getWords().size() - 1];
                }
                future.complete(new Lyrics.TimedLyric(originalLyric.startTimeMs(), duration, transcriptionResult.getText(), wordRelativeTimingsStart, wordRelativeTimingsEnd));
            } catch (Exception e){
                e.printStackTrace();
                var numWords = originalLyric.words().split(" ").length;
                long[] begins = new long[numWords];
                long[] ends = new long[numWords];
                for (int i = 0; i < numWords; i++) {
                    begins[i] = 0;
                    ends[i] = 0;
                }
                future.complete(new Lyrics.TimedLyric(originalLyric.startTimeMs(), (long) (nextLyricStartMs - originalLyric.startTimeMs()), originalLyric.words(), begins, ends));
            }

        });

        return future;
//        List<Words> transcribedWords = transcriptionResult.getWords();
//
//        int originalWordsIndex = 0;
//        int transcribedWordsIndex = 0;
//
//        TempLyric tempLyric = new TempLyric();
//
//        LinkedList<DiffMatchPatch.Diff> diffsLinkedList = dmp.diffMain(String.join(" ", originalWords).toLowerCase().replace("\n", ""),
//                transcribedWords.stream().map(Words::getWord).collect(Collectors.joining(" ")).toLowerCase()
//        );
//
//        List<DiffMatchPatch.Diff> diffs = diffsLinkedList.stream().toList();
//        System.out.println(diffs);
//
//        Map<DiffMatchPatch.Operation, DiffMatchPatch.Operation> oppositeOperations = Map.of(
//                DiffMatchPatch.Operation.INSERT, DiffMatchPatch.Operation.DELETE,
//                DiffMatchPatch.Operation.DELETE, DiffMatchPatch.Operation.INSERT
//        );
//
//        System.out.println("Starting Matching");
//        System.out.printf("Original Len: %d, Transcribed Len: %d", originalWords.size(), transcribedWords.size());
//
//        for (int i = 0; i < diffs.size(); i++) {
//            DiffMatchPatch.Diff diff = diffs.get(i);
//            if (diff.operation == DiffMatchPatch.Operation.EQUAL) {
//                var wordsToAdd = countSpaces(diff.text);
//                for (int j = 0; j < wordsToAdd; j++) {
//                    tempLyric.addNewWord(originalWords.get(originalWordsIndex), transcribedWords.get(transcribedWordsIndex), lyrics, originalLyrics);
//                    System.out.printf("Matched: %s -> %s, index: %d, %d%n", originalWords.get(originalWordsIndex), transcribedWords.get(transcribedWordsIndex), originalWordsIndex, transcribedWordsIndex);
//                    originalWordsIndex++;
//                    transcribedWordsIndex++;
//                }
//            } else {
//                boolean isNextItem = i + 1 < diffs.size();
//                boolean isNextOperationOpposite = false;
//                long wordsInThisDiff = 0;
//                long wordsInNextDiff = 0;
//                if (isNextItem) {
//                    isNextOperationOpposite = diffs.get(i + 1).operation == oppositeOperations.get(diff.operation);
//                    wordsInThisDiff = countSpaces(diff.text);
//                    wordsInNextDiff = countSpaces(diffs.get(i + 1).text);
//                }
//
//                if (isNextItem && isNextOperationOpposite && (wordsInThisDiff == wordsInNextDiff)) {
//                    // Some words were switched around
//                    var wordsToAdd = countSpaces(diff.text);
//                    for (int j = 0; j < wordsToAdd; j++) {
//                        tempLyric.addNewWord(originalWords.get(originalWordsIndex), transcribedWords.get(transcribedWordsIndex), lyrics, originalLyrics);
//                        System.out.printf("Mismatched: %s -> %s, index: %d, %d%n", originalWords.get(originalWordsIndex), transcribedWords.get(transcribedWordsIndex), originalWordsIndex, transcribedWordsIndex);
//                        originalWordsIndex++;
//                        transcribedWordsIndex++;
//                    }
//                } else if (diff.operation == DiffMatchPatch.Operation.INSERT) {
//                    var wordsToAdd = countSpaces(diff.text);
//
//                    // Drop these words from the transcript
//                    for (int j = 0; j < wordsToAdd; j++) {
//                        System.out.printf("Extra Word: %s, Diff: %s index: %d, %d%n", transcribedWords.get(transcribedWordsIndex), diff.text, originalWordsIndex, transcribedWordsIndex);
//                        transcribedWordsIndex++;
//                    }
//                } else if (diff.operation == DiffMatchPatch.Operation.DELETE) {
//                    var wordsToAdd = countSpaces(diff.text);
//                    // We're missing these words from the transcript
//
//                    double lastTranscribedWordEnd = 0;
//                    if (transcribedWordsIndex > 0) {
//                        lastTranscribedWordEnd = transcribedWords.get(transcribedWordsIndex - 1).getEnd();
//                    }
//                    double nextTranscribedWordBegin = lastTranscribedWordEnd;
//                    if (transcribedWordsIndex < transcribedWords.size()) {
//                        nextTranscribedWordBegin = transcribedWords.get(transcribedWordsIndex).getStart();
//                    }
//
//                    double eachWordTime = (nextTranscribedWordBegin - lastTranscribedWordEnd) / (double) (wordsToAdd + 1);
//                    System.out.printf("Word Each Time %f, lastEnd %f, nextStart %f, words to add %d %n", eachWordTime, lastTranscribedWordEnd, nextTranscribedWordBegin, wordsToAdd);
//
//                    for (int j = 0; j < wordsToAdd; j++) {
//                        var start = lastTranscribedWordEnd + eachWordTime * (j + 1);
//                        var end = lastTranscribedWordEnd + eachWordTime * (j + 2);
//                        System.out.printf("Missing Word: %s, Diff: %s, Placing @ %s index: %d, %d%n", originalWords.get(originalWordsIndex), diff.text, start, originalWordsIndex, transcribedWordsIndex);
//                        tempLyric.addNewWord(originalWords.get(originalWordsIndex), start, end, lyrics, originalLyrics);
//                        originalWordsIndex++;
//                    }
//                }
//            }
//        }
//
//        if (!tempLyric.tempWords.isEmpty()) {
//            lyrics.lyrics.add(tempLyric.getTimedLyric());
//        }
    }
}
