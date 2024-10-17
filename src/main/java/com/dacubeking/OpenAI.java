package com.dacubeking;

import com.theokanning.openai.audio.CreateTranscriptionRequest;
import com.theokanning.openai.audio.TranscriptionResult;
import com.theokanning.openai.service.OpenAiService;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class OpenAI {
    private static OpenAiService service;

    private static ConcurrentHashMap<String, TranscriptionResult> transcriptionResultCache = new ConcurrentHashMap<>();

    static {
        service = new OpenAiService(Duration.ofSeconds(30));
    }




    public static TranscriptionResult getTranscription(File musicFile, String id, String prompt){

        return  transcriptionResultCache.computeIfAbsent(id, k -> service.createTranscription(
                CreateTranscriptionRequest.builder()
                        .model("whisper-1")
                        .timestampGranularities(List.of("word"))
                        .responseFormat("verbose_json")
                        .prompt(prompt)
                        .build(),
                musicFile));
    }

}
