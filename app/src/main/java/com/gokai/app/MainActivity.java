package com.gokai.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        webView.addJavascriptInterface(new GokAIBridge(), "GokAIAndroid");

        webView.loadUrl("file:///android_asset/index.html");
    }

    public class GokAIBridge {

        @JavascriptInterface
        public void saveApiKey(String key) {
            getSharedPreferences("gokai", MODE_PRIVATE)
                    .edit()
                    .putString("groq_key", key)
                    .apply();
        }

        @JavascriptInterface
        public String getApiKey() {
            return getSharedPreferences("gokai", MODE_PRIVATE)
                    .getString("groq_key", "");
        }

        @JavascriptInterface
        public void ask(final String text) {

            new Thread(new Runnable() {
                @Override
                public void run() {

                    try {

                        String apiKey = getSharedPreferences(
                                "gokai",
                                MODE_PRIVATE
                        ).getString("groq_key", "");

                        if (apiKey.isEmpty()) {
                            sendToPage(
                                    "Önce Groq API anahtarını girmen gerekiyor."
                            );
                            return;
                        }

                        URL url = new URL(
                                "https://api.groq.com/openai/v1/chat/completions"
                        );

                        HttpURLConnection connection =
                                (HttpURLConnection) url.openConnection();

                        connection.setRequestMethod("POST");
                        connection.setRequestProperty(
                                "Authorization",
                                "Bearer " + apiKey
                        );
                        connection.setRequestProperty(
                                "Content-Type",
                                "application/json"
                        );

                        connection.setDoOutput(true);

                        JSONObject body = new JSONObject();

                        body.put(
                                "model",
                                "llama-3.1-8b-instant"
                        );

                        JSONArray messages = new JSONArray();

                        JSONObject system = new JSONObject();
                        system.put("role", "system");
                        system.put(
                                "content",
                                "Sen GökAI adlı yardımcı bir yapay zekasın. " +
                                "Türkçe sorulara doğal, hızlı ve faydalı cevap ver."
                        );

                        JSONObject user = new JSONObject();
                        user.put("role", "user");
                        user.put("content", text);

                        messages.put(system);
                        messages.put(user);

                        body.put("messages", messages);

                        OutputStream os =
                                connection.getOutputStream();

                        os.write(
                                body.toString().getBytes("UTF-8")
                        );

                        os.close();

                        int responseCode =
                                connection.getResponseCode();

                        BufferedReader reader;

                        if (responseCode >= 200 &&
                                responseCode < 300) {

                            reader = new BufferedReader(
                                    new InputStreamReader(
                                            connection.getInputStream()
                                    )
                            );

                        } else {

                            reader = new BufferedReader(
                                    new InputStreamReader(
                                            connection.getErrorStream()
                                    )
                            );
                        }

                        StringBuilder result =
                                new StringBuilder();

                        String line;

                        while ((line = reader.readLine())
                                != null) {
                            result.append(line);
                        }

                        reader.close();

                        if (responseCode >= 200 &&
                                responseCode < 300) {

                            JSONObject response =
                                    new JSONObject(
                                            result.toString()
                                    );

                            String answer =
                                    response
                                            .getJSONArray("choices")
                                            .getJSONObject(0)
                                            .getJSONObject("message")
                                            .getString("content");

                            sendToPage(answer);

                        } else {

                            sendToPage(
                                    "API hatası: " +
                                    responseCode +
                                    "\n" +
                                    result.toString()
                            );
                        }

                    } catch (Exception e) {

                        sendToPage(
                                "Bağlantı hatası: " +
                                e.getMessage()
                        );
                    }
                }
            }).start();
        }
    }

    private void sendToPage(final String text) {

        runOnUiThread(new Runnable() {
            @Override
            public void run() {

                String safe =
                        JSONObject.quote(text);

                webView.evaluateJavascript(
                        "window.onAIResult(" +
                                safe +
                                ");",
                        null
                );
            }
        });
    }
}
