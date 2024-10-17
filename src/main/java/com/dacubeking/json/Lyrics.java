package com.dacubeking.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;

public class Lyrics {
    public String error;

    public boolean isRtlLanguage;

    public String language;

    public ArrayList<TimedLyric> lyrics = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimedLyric(long startTimeMs, long durationMs, String words, long[] wordRelativeTimingsStart, long[] wordRelativeTimingsEnd) {}

    public String trackId;

}
