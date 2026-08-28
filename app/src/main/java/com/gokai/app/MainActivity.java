package com.gokai.app;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.content.ActivityNotFoundException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

public class MainActivity extends Activity {

    private WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private static final int VOICE_REQ = 1002;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "GokAIAndroid");

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public class Bridge {

        @JavascriptInterface
        public void ask(
                final String requestId,
                final String prompt,
                final String mode,
                final boolean searchWeb
        ) {
            pool.submit(() -> {

                try {
                    String webInfo = "";

                    if (searchWeb || looksCurrent(prompt)) {
                        webInfo = searchWeb(prompt);
                    }

                    String answer;

                    if (isCreatorQuestion(prompt)) {
                        answer = "Benim yaratıcım Göktuğ Ege Genç.";
                    } else {
                        answer = askAI(prompt, mode, webInfo);
                    }

                    sendResult(requestId, answer, webInfo);

                } catch (Exception e) {
                    sendResult(
                            requestId,
                            "Bağlantı sırasında hata oluştu: " + e.getMessage(),
                            ""
                    );
                }
            });
        }

        @JavascriptInterface
        public void startVoice() {

            runOnUiThread(() -> {

                if (android.os.Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED) {

                    requestPermissions(
                            new String[]{Manifest.permission.RECORD_AUDIO},
                            123
                    );
                }

                Intent intent =
                        new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

                intent.putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        "tr-TR"
                );

                intent.putExtra(
                        RecognizerIntent.EXTRA_PROMPT,
                        "GökAI dinliyor…"
                );

                try {
                    startActivityForResult(intent, VOICE_REQ);
                } catch (ActivityNotFoundException e) {
                    sendJs("window.onVoiceResult('');");
                }
            });
        }
    }

    private boolean isCreatorQuestion(String text) {

        String s =
                text.toLowerCase(new Locale("tr", "TR"));

        return
                s.contains("yaratıcın kim") ||
                s.contains("seni kim yaptı") ||
                s.contains("seni kim yarattı");
    }

    private boolean looksCurrent(String text) {

        String s =
                text.toLowerCase(new Locale("tr", "TR"));

        String[] words = {
                "bugün",
                "şu an",
                "şimdi",
                "güncel",
                "son dakika",
                "haber",
                "hava",
                "dolar",
                "euro",
                "altın",
                "maç",
                "kaç kaç",
                "fiyat",
                "kim kazandı",
                "sonuç",
                "son durum"
        };

        for (String word : words) {
            if (s.contains(word)) {
                return true;
            }
        }

        return false;
    }

    private String askAI(
            String prompt,
            String mode,
            String webInfo
    ) throws Exception {

        String system =
                "Sen GökAI isimli bağımsız Türkçe bir yapay zekâ asistanısın. " +
                "Yaratıcın Göktuğ Ege Genç'tir. " +
                "Bilmediğin bilgileri uydurma. ";

        if ("fast".equals(mode)) {
            system += "Kısa, hızlı ve doğrudan cevap ver. ";
        }

        if ("think".equals(mode)) {
            system +=
                    "Soruyu dikkatlice değerlendir ve anlaşılır cevap ver. ";
        }

        if ("work".equals(mode)) {
            system +=
                    "Görevi adımlara böl ve uygulanabilir şekilde tamamla. ";
        }

        if (!webInfo.isEmpty()) {

            system +=
                    "\nGüncel web aramasından gelen bilgiler:\n" +
                    webInfo +
                    "\nBu bilgileri gerekiyorsa cevabında kullan.";
        }

        String fullPrompt =
                system +
                "\n\nKullanıcı:\n" +
                prompt;

        String urlText =
                "https://text.pollinations.ai/" +
                URLEncoder.encode(fullPrompt, "UTF-8");

        URL url = new URL(urlText);
