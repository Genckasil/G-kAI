package com.gokai.app;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.speech.RecognizerIntent;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
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
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "GokAIAndroid");
        web.loadUrl("file:///android_asset/index.html");
    }

    public class Bridge {
        @JavascriptInterface
        public void ask(String requestId, String prompt, String mode, boolean searchWeb) {
            pool.submit(() -> {
                try {
                    String answer = creatorQuestion(prompt)
                            ? "Benim yaratıcım Göktuğ Ege Genç."
                            : askAI(prompt, mode);
                    sendResult(requestId, answer, "");
                } catch (Exception e) {
                    sendResult(requestId, "Bağlantı hatası: " + e.getMessage(), "");
                }
            });
        }

        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(() -> {
                if (android.os.Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
                    return;
                }
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
                startActivityForResult(i, VOICE_REQ);
            });
        }
    }

    private boolean creatorQuestion(String text) {
        String s = text.toLowerCase(new Locale("tr", "TR"));
        return s.contains("yaratıcın kim") || s.contains("seni kim yaptı") || s.contains("seni kim yarattı");
    }

    private String askAI(String prompt, String mode) throws Exception {
        String inst = "Sen GökAI isimli Türkçe bir yapay zekâ asistanısın. Yaratıcın Göktuğ Ege Genç'tir. Bilmediğin şeyi uydurma. ";
        if ("fast".equals(mode)) inst += "Kısa ve hızlı cevap ver. ";
        else if ("think".equals(mode)) inst += "Dikkatli ve açıklayıcı cevap ver. ";
        else inst += "Görevi adım adım tamamla. ";

        String full = inst + "\n\nKullanıcı:\n" + prompt;
        URL url = new URL("https://text.pollinations.ai/" + URLEncoder.encode(full, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);

        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) out.append(line).append("\n");
        r.close();
        return out.toString().trim();
    }

    private void sendResult(String id, String answer, String sources) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("answer", answer);
            o.put("sources", sources);
            String js = "window.onNativeResult(" + JSONObject.quote(o.toString()) + ");";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQ && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String text = (results != null && !results.isEmpty()) ? results.get(0) : "";
            web.evaluateJavascript("window.onVoiceResult(" + JSONObject.quote(text) + ");", null);
        }
    }
}
