package com.gokai.app;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.webkit.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private WebView web;
    private TextToSpeech tts;
    private boolean voiceMode = false;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private static final int VOICE_REQ = 101;
    private static final int FILE_REQ = 102;

    private static final String PREFS = "gokai";
    private static final String KEY_API = "groq_api_key";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tts = new TextToSpeech(this, this);

        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());

        web.addJavascriptInterface(new Bridge(), "GokAIAndroid");

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {

            tts.setLanguage(new Locale("tr", "TR"));
            tts.setSpeechRate(1.03f);
            tts.setPitch(1.0f);

            tts.setOnUtteranceProgressListener(
                    new UtteranceProgressListener() {

                        @Override
                        public void onStart(String utteranceId) {
                        }

                        @Override
                        public void onError(String utteranceId) {
                        }

                        @Override
                        public void onDone(String utteranceId) {

                            if (voiceMode) {
                                runOnUiThread(() -> beginVoiceInput());
                            }
                        }
                    }
            );
        }
    }

    public class Bridge {

        @JavascriptInterface
        public String getApiKey() {

            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_API, "");
        }

        @JavascriptInterface
        public void saveApiKey(String key) {

            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(
                            KEY_API,
                            key == null ? "" : key.trim()
                    )
                    .apply();
        }

        @JavascriptInterface
        public void ask(
                String id,
                String messagesJson,
                String mode,
                boolean webSearch,
                String attachmentJson
        ) {

            pool.submit(() -> {

                try {

                    String key = getApiKey();

                    if (key.isEmpty()) {

                        sendResult(
                                id,
                                "Önce Groq API anahtarını kaydet.",
                                ""
                        );

                        return;
                    }

                    String answer =
                            callGroq(
                                    key,
                                    messagesJson,
                                    mode,
                                    webSearch,
                                    attachmentJson
                            );

                    sendResult(
                            id,
                            answer,
                            ""
                    );

                } catch (Exception e) {

                    sendResult(
                            id,
                            "GökAI bağlantı hatası: " + friendlyError(e),
                            ""
                    );
                }
            });
        }

        @JavascriptInterface
        public void pickFile() {

            runOnUiThread(() -> {

                Intent intent =
                        new Intent(Intent.ACTION_OPEN_DOCUMENT);

                intent.addCategory(
                        Intent.CATEGORY_OPENABLE
                );

                intent.setType("*/*");

                startActivityForResult(
                        intent,
                        FILE_REQ
                );
            });
        }

        @JavascriptInterface
        public void toggleVoice(boolean enabled) {

            voiceMode = enabled;

            if (!enabled) {

                if (tts != null) {
                    tts.stop();
                }

                return;
            }

            runOnUiThread(
                    () -> beginVoiceInput()
            );
        }

        @JavascriptInterface
        public void speak(String text) {

            if (
                    tts == null ||
                    text == null ||
                    text.trim().isEmpty()
            ) {
                return;
            }

            Bundle params = new Bundle();

            tts.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    params,
                    "gokai_reply"
            );
        }
    }

    private String getApiKey() {

        return getSharedPreferences(
                PREFS,
                MODE_PRIVATE
        ).getString(
                KEY_API,
                ""
        );
    }

    private String callGroq(
