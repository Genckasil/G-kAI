package com.gokai.app;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView webView;
    private TextToSpeech tts;

    private static final int VOICE_REQ = 101;
    private static final int FILE_REQ = 102;
    private static final int MIC_PERMISSION = 103;

    private String pendingImage = "";
    private String pendingFileText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        webView.addJavascriptInterface(new GokAIBridge(), "GokAIAndroid");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("tr", "TR"));
                tts.setSpeechRate(1.0f);
            }
        });

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
        public void ask(String text) {
            askAI(text, "Hızlı", false);
        }

        @JavascriptInterface
        public void askAdvanced(String text, String mode, boolean webSearch) {
            askAI(text, mode, webSearch);
        }

        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(() -> beginVoice());
        }

        @JavascriptInterface
        public void speak(String text) {
            runOnUiThread(() -> {
                if (tts != null) {
                    tts.speak(
                            text,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "gokai"
                    );
                }
            });
        }

        @JavascriptInterface
        public void pickFile() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        new String[]{
                                "image/*",
                                "text/plain",
                                "application/json",
                                "text/csv"
                        }
                );
                startActivityForResult(intent, FILE_REQ);
            });
        }
    }

    private void beginVoice() {

        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MIC_PERMISSION
            );
            return;
        }

        Intent intent = new Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "tr-TR"
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "GökAI dinliyor..."
        );

        try {
            startActivityForResult(intent, VOICE_REQ);
        } catch (Exception e) {
            sendJs("window.onVoiceError('Ses tanıma açılamadı.');");
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == MIC_PERMISSION &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            beginVoice();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        if (requestCode == VOICE_REQ) {

            ArrayList<String> results =
                    data.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS
                    );

            if (results != null && !results.isEmpty()) {

                String spoken = results.get(0);

                sendJs(
                        "window.onVoiceResult(" +
                                JSONObject.quote(spoken) +
                                ");"
                );
            }
        }

        if (requestCode == FILE_REQ) {

            Uri uri = data.getData();

            if (uri != null) {
                handleFile(uri);
            }
        }
    }

    private void handleFile(Uri uri) {

        new Thread(() -> {

            try {

                String mime =
                        getContentResolver().getType(uri);

                String name = getFileName(uri);

                InputStream input =
                        getContentResolver().openInputStream(uri);

                if (input == null) {
                    throw new Exception("Dosya açılamadı.");
                }

                ByteArrayOutputStream output =
                        new ByteArrayOutputStream();

                byte[] buffer = new byte[8192];
                int read;
                int total = 0;

                while ((read = input.read(buffer)) != -1) {

                    total += read;

                    if (total > 4 * 1024 * 1024) {
                        input.close();
                        throw new Exception(
                                "Dosya şu an en fazla 4 MB olabilir."
                        );
                    }

                    output.write(buffer, 0, read);
                }

                input.close();

                byte[] bytes = output.toByteArray();

                if (mime != null &&
                        mime.startsWith("image/")) {

                    pendingImage =
                            "data:" +
                                    mime +
                                    ";base64," +
                                    Base64.encodeToString(
                                            bytes,
                                            Base64.NO_WRAP
                                    );

                    pendingFileText = "";

                } else {

                    pendingFileText =
                            new String(bytes, "UTF-8");

                    pendingImage = "";
                }

                sendJs(
                        "window.onFilePicked(" +
                                JSONObject.quote(name) +
                                ");"
                );

            } catch (Exception e) {

                sendJs(
                        "window.onFileError(" +
                                JSONObject.quote(
                                        e.getMessage()
                                ) +
                                ");"
                );
            }

        }).start();
    }

    private String getFileName(Uri uri) {

        String result = "dosya";

        Cursor cursor = null;

        try {

            cursor = getContentResolver().query(
                    uri,
                    null,
                    null,
                    null,
                    null
            );

            if (cursor != null &&
                    cursor.moveToFirst()) {

                int index =
                        cursor.getColumnIndex(
                                OpenableColumns.DISPLAY_NAME
                        );

                if (index >= 0) {
                    result = cursor.getString(index);
                }
            }

        } catch (Exception ignored) {
        } finally {

            if (cursor != null) {
                cursor.close();
            }
        }

        return result;
    }

    private void askAI(
            String text,
            String mode,
            boolean webSearch) {

        new Thread(() -> {

            try {

                String apiKey =
                        getSharedPreferences(
                                "gokai",
                                MODE_PRIVATE
                        ).getString("groq_key", "");

                if (apiKey.isEmpty()) {

                    sendAnswer(
                            "Önce sağ üstteki ⚙ bölümünden Groq API anahtarını gir."
                    );

                    return;
                }

                String model;

                if (!pendingImage.isEmpty()) {

                    model = "qwen/qwen3.6-27b";

                } else if (webSearch) {

                    if ("Hızlı".equals(mode)) {
                        model = "groq/compound-mini";
                    } else {
                        model = "groq/compound";
                    }

                } else if ("Düşün".equals(mode) ||
                        "Çalışma".equals(mode)) {

                    model = "openai/gpt-oss-120b";

                } else {

                    model = "openai/gpt-oss-20b";
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

                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setDoOutput(true);

                JSONObject body = new JSONObject();

                body.put("model", model);

                JSONArray messages = new JSONArray();

                JSONObject system = new JSONObject();

                system.put("role", "system");

                system.put(
                        "content",
                        "Sen GökAI adlı Türkçe konuşan yardımcı bir yapay zekasın. " +
                        "Doğal, anlaşılır ve faydalı cevaplar ver."
                );

                messages.put(system);

                JSONObject user = new JSONObject();

                user.put("role", "user");

                if (!pendingImage.isEmpty()) {

                    JSONArray content =
                            new JSONArray();

                    JSONObject textPart =
                            new JSONObject();

                    textPart.put(
                            "type",
                            "text"
                    );

                    textPart.put(
                            "text",
                            text
                    );

                    JSONObject imagePart =
                            new JSONObject();

                    imagePart.put(
                            "type",
                            "image_url"
                    );

                    JSONObject imageUrl =
                            new JSONObject();

                    imageUrl.put(
                            "url",
                            pendingImage
                    );

                    imagePart.put(
                            "image_url",
                            imageUrl
                    );

                    content.put(textPart);
                    content.put(imagePart);

                    user.put(
                            "content",
                            content
                    );

                } else {

                    String finalText = text;

                    if (!pendingFileText.isEmpty()) {

                        finalText +=
                                "\n\nEklenen dosyanın içeriği:\n" +
                                        pendingFileText;
                    }

                    user.put(
                            "content",
                            finalText
                    );
                }

                messages.put(user);

                body.put(
                        "messages",
                        messages
                );

                OutputStream os =
                        connection.getOutputStream();

                os.write(
                        body.toString()
                                .getBytes("UTF-8")
                );

                os.close();

                int code =
                        connection.getResponseCode();

                BufferedReader reader;

                if (code >= 200 && code < 300) {

                    reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            connection.getInputStream()
                                    )
                            );

                } else {

                    reader =
                            new BufferedReader(
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

                pendingImage = "";
                pendingFileText = "";

                if (code >= 200 &&
                        code < 300) {

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

                    sendAnswer(answer);

                } else {

                    sendAnswer(
                            "API hatası " +
                                    code +
                                    ": " +
                                    result
                    );
                }

            } catch (Exception e) {

                sendAnswer(
                        "Bağlantı hatası: " +
                                e.getMessage()
                );
            }

        }).start();
    }

    private void sendAnswer(String answer) {

        sendJs(
                "window.onAIResult(" +
                        JSONObject.quote(answer) +
                        ");"
        );
    }

    private void sendJs(String javascript) {

        runOnUiThread(() ->
                webView.evaluateJavascript(
                        javascript,
                        null
                )
        );
    }

    @Override
    protected void onDestroy() {

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        super.onDestroy();
    }
}
