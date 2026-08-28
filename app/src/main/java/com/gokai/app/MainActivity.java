package com.gokai.app;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.speech.RecognizerIntent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final int VOICE_REQ = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "GokAIAndroid");

        web.loadUrl("file:///android_asset/index.html");
    }

    public class Bridge {

        @JavascriptInterface
        public void ask(String id, String prompt, String mode, boolean webSearch) {
            pool.submit(() -> {
                try {
                    String answer;

                    if (creatorQuestion(prompt)) {
                        answer = "Benim yaratıcım Göktuğ Ege Genç.";
                    } else {
                        answer = askAI(prompt, mode);
                    }

                    sendResult(id, answer);

                } catch (Exception e) {
                    sendResult(id, "Bağlantı hatası: " + e.getMessage());
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
                        10
                    );

                    return;
                }

                Intent intent =
                    new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

                intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "tr-TR"
                );

                startActivityForResult(intent, VOICE_REQ);
            });
        }
    }

    private boolean creatorQuestion(String text) {

        String s =
            text.toLowerCase(new Locale("tr", "TR"));

        return
            s.contains("yaratıcın kim") ||
            s.contains("seni kim yaptı") ||
            s.contains("seni kim yarattı");
    }

    private String askAI(String prompt, String mode) throws Exception {

        String instruction =
            "Sen GökAI isimli Türkçe bir yapay zekâ asistanısın. " +
            "Yaratıcın Göktuğ Ege Genç'tir. " +
            "Bilmediğin bilgiyi uydurma. ";

        if ("fast".equals(mode)) {
            instruction += "Kısa ve hızlı cevap ver. ";
        } else if ("think".equals(mode)) {
            instruction += "Dikkatlice düşün ve açıklayıcı cevap ver. ";
        } else {
            instruction += "Görevi adım adım tamamla. ";
        }

        String fullPrompt =
            instruction +
            "\n\nKullanıcı:\n" +
            prompt;

        String address =
            "https://text.pollinations.ai/" +
            URLEncoder.encode(fullPrompt, "UTF-8");

        URL url = new URL(address);

        HttpURLConnection connection =
            (HttpURLConnection) url.openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);

        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(
                    connection.getInputStream()
                )
            );

        StringBuilder result = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            result.append(line).append("\n");
        }

        reader.close();

        return result.toString().trim();
    }

    private void sendResult(String id, String answer) {

        try {
            JSONObject object = new JSONObject();

            object.put("id", id);
            object.put("answer", answer);
            object.put("sources", "");

            String javascript =
                "window.onNativeResult(" +
                JSONObject.quote(object.toString()) +
                ");";

            runOnUiThread(() ->
                web.evaluateJavascript(javascript, null)
            );

        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(
        int requestCode,
        int resultCode,
        Intent data
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        );

        if (
            requestCode == VOICE_REQ &&
            resultCode == RESULT_OK &&
            data != null
        ) {

            ArrayList<String> results =
                data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS
                );

            String text =
                results != null && !results.isEmpty()
                ? results.get(0)
                : "";

            String javascript =
                "window.onVoiceResult(" +
                JSONObject.quote(text) +
                ");";

            web.evaluateJavascript(javascript, null);
        }
    }
}
