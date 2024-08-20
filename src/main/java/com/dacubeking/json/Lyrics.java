package com.dacubeking.json;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;

public class Lyrics {
    public String error;

    public boolean isRtlLanguage;

    public String language;

    public ArrayList<TimedLyrics> lyrics;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimedLyrics(long startTimeMs, long durationMs, String words, long[] wordRelativeTimings){}

    public String trackId;
}
