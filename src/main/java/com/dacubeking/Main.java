package com.dacubeking;

import com.dacubeking.json.Lyrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.net.URLEncodedUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static final ObjectMapper mapper = new ObjectMapper();


    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8000), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/getLyrics", (httpExchange) -> {
            httpExchange.getRequestBody().readAllBytes();
            httpExchange.getRequestBody().close();
            // Get json from the body
            var uri = new URIBuilder(httpExchange.getRequestURI());
            var song = uri.getQueryParams().stream().filter(nameValuePair -> nameValuePair.getName().equals("s")).findFirst();
            var artist = uri.getQueryParams().stream().filter(nameValuePair -> nameValuePair.getName().equals("a")).findFirst();
            var videoId = uri.getQueryParams().stream().filter(nameValuePair -> nameValuePair.getName().equals("v")).findFirst();

            if (song.isEmpty() || artist.isEmpty() || videoId.isEmpty()) {
                System.out.println(httpExchange.getRequestURI());
                httpExchange.sendResponseHeaders(400 , 0);
                httpExchange.getResponseBody().close();
                return;
            }

            //System.out.printf("Song %s, Artist %s, VideoID %s%n", song.get().getValue(), artist.get().getValue(), videoId.get().getValue());


            Optional<String> lyricsJson = ProcessLyrics.getLyrics(videoId.get().getValue(), () -> {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://lyrics-api.boidu.dev/getLyrics?s="
                                + URLEncoder.encode(song.get().getValue(), Charset.defaultCharset())
                                + "&a=" + URLEncoder.encode(artist.get().getValue(), Charset.defaultCharset())))
                        .build();

                try {
                    var httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
                    var body = httpResponse.body();
                    //System.out.println(body);
                    return Optional.of(mapper.readValue(body, Lyrics.class));
                } catch (Exception e) {
                    e.printStackTrace();
                    return Optional.empty();
                }
            });

            String body = lyricsJson.orElse("404");



            var bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            httpExchange.getResponseHeaders().add("Content-Type", "application/json");
            httpExchange.getResponseHeaders().add("Access-Control-Allow-Origin", "https://music.youtube.com");
            httpExchange.sendResponseHeaders(200, bodyBytes.length);
            httpExchange.getResponseBody().write(bodyBytes);
            httpExchange.getResponseBody().close();


        });

        server.start();

    }
}