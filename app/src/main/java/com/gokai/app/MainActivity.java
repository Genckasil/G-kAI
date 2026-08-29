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

    private static final int MAX_FILE_BYTES = 500000;
    private static final int MAX_FILE_TEXT = 12000;
    private static final int MAX_IMAGE_BYTES = 3500000;

    private String pendingImage = "";
    private String pendingImageMime = "";
    private String pendingFileText = "";
    private String pendingFileName = "";

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

        webView.addJavascriptInterface(
                new GokAIBridge(),
                "GokAIAndroid"
        );

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
        public void askAdvanced(
                String text,
                String mode,
                boolean webSearch
        ) {
            askAI(text, mode, webSearch);
        }

        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(() -> beginVoice());
        }

        @JavascriptInterface
        public void speak(String text) {
            runOnUiThread(() -> {
                if (tts != null &&
                        text != null &&
                        !text.trim().isEmpty()) {

                    tts.speak(
                            text,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "gokai_reply"
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

                intent.putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        new String[]{
                                "image/*",
                                "text/plain",
                                "application/json",
                                "text/csv"
                        }
                );

                startActivityForResult(
                        intent,
                        FILE_REQ
                );
            });
        }

        @JavascriptInterface
        public void clearAttachment() {
            clearPendingAttachment();
        }
    }

    private void beginVoice() {

        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.RECORD_AUDIO
                    },
                    MIC_PERMISSION
            );

            return;
        }

        Intent intent =
                new Intent(
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

            startActivityForResult(
                    intent,
                    VOICE_REQ
            );

        } catch (Exception e) {

            sendJs(
                    "window.onVoiceError(" +
                            JSONObject.quote(
                                    "Ses tanıma açılamadı."
                            ) +
                            ");"
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == MIC_PERMISSION &&
                grantResults.length > 0 &&
                grantResults[0] ==
                        PackageManager.PERMISSION_GRANTED) {

            beginVoice();
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

        if (resultCode != RESULT_OK ||
                data == null) {
            return;
        }

        if (requestCode == VOICE_REQ) {

            ArrayList<String> results =
                    data.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS
                    );

            if (results != null &&
                    !results.isEmpty()) {

                sendJs(
                        "window.onVoiceResult(" +
                                JSONObject.quote(
                                        results.get(0)
                                ) +
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

                clearPendingAttachment();

                String mime =
                        getContentResolver()
                                .getType(uri);

                String name =
                        getFileName(uri);

                InputStream input =
                        getContentResolver()
                                .openInputStream(uri);

                if (input == null) {
                    throw new Exception(
                            "Dosya açılamadı."
                    );
                }

                boolean isImage =
                        mime != null &&
                                mime.startsWith(
                                        "image/"
                                );

                int maxBytes =
                        isImage
                                ? MAX_IMAGE_BYTES
                                : MAX_FILE_BYTES;

                ByteArrayOutputStream output =
                        new ByteArrayOutputStream();

                byte[] buffer =
                        new byte[8192];

                int read;
                int total = 0;

                while ((read =
                        input.read(buffer)) != -1) {

                    total += read;

                    if (total > maxBytes) {

                        input.close();

                        throw new Exception(
                                isImage
                                        ? "Resim en fazla yaklaşık 3,5 MB olabilir."
                                        : "Metin dosyası fazla büyük."
                        );
                    }

                    output.write(
                            buffer,
                            0,
                            read
                    );
                }

                input.close();

                byte[] bytes =
                        output.toByteArray();

                pendingFileName = name;

                if (isImage) {

                    pendingImageMime = mime;

                    pendingImage =
                            "data:" +
                                    mime +
                                    ";base64," +
                                    Base64.encodeToString(
                                            bytes,
                                            Base64.NO_WRAP
                                    );

                } else {

                    String text =
                            new String(
                                    bytes,
                                    "UTF-8"
                            );

                    if (text.length() >
                            MAX_FILE_TEXT) {

                        text =
                                text.substring(
                                        0,
                                        MAX_FILE_TEXT
                                ) +
                                "\n\n[Dosyanın devamı boyut sınırı nedeniyle kesildi.]";
                    }

                    pendingFileText = text;
                }

                sendJs(
                        "window.onFilePicked(" +
                                JSONObject.quote(name) +
                                ");"
                );

            } catch (Exception e) {

                clearPendingAttachment();

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

            cursor =
                    getContentResolver()
                            .query(
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
                                OpenableColumns
                                        .DISPLAY_NAME
                        );

                if (index >= 0) {
                    result =
                            cursor.getString(
                                    index
                            );
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
            boolean webSearch
    ) {

        new Thread(() -> {

            try {

                String apiKey =
                        getSharedPreferences(
                                "gokai",
                                MODE_PRIVATE
                        ).getString(
                                "groq_key",
                                ""
                        );

                if (apiKey.isEmpty()) {

                    sendAnswer(
                            "Önce sağ üstteki ⚙ bölümünden Groq API anahtarını gir."
                    );

                    return;
                }

                if (text == null) {
                    text = "";
                }

                text = text.trim();

                if (text.length() > 8000) {
                    text =
                            text.substring(
                                    0,
                                    8000
                            );
                }

                /*
                 * WEB MODU:
                 * Dosya ve base64 resim kesinlikle
                 * web isteğine eklenmiyor.
                 * Böylece 413 riski büyük ölçüde azalır.
                 */
                if (webSearch) {

                    callTextModel(
                            apiKey,
                            text,
                            mode,
                            true
                    );

                    return;
                }

                /*
                 * Resim varsa vision modeli.
                 */
                if (!pendingImage.isEmpty()) {

                    callVisionModel(
                            apiKey,
                            text
                    );

                    return;
                }

                /*
                 * Normal metin + küçük metin dosyası.
                 */
                String finalText = text;

                if (!pendingFileText.isEmpty()) {

                    finalText +=
                            "\n\nEkli dosya: " +
                                    pendingFileName +
                                    "\n\n" +
                                    pendingFileText;
                }

                callTextModel(
                        apiKey,
                        finalText,
                        mode,
                        false
                );

            } catch (Exception e) {

                clearPendingAttachment();

                sendAnswer(
                        "Bağlantı hatası: " +
                                e.getMessage()
                );
            }

        }).start();
    }

    private void callTextModel(
            String apiKey,
            String text,
            String mode,
            boolean webSearch
    ) throws Exception {

        String model;

        if (webSearch) {

            model =
                    "Hızlı".equals(mode)
                            ? "groq/compound-mini"
                            : "groq/compound";

        } else if (
                "Düşün".equals(mode) ||
                        "Çalışma".equals(mode)
        ) {

            model =
                    "openai/gpt-oss-120b";

        } else {

            model =
                    "openai/gpt-oss-20b";
        }

        JSONObject body =
                new JSONObject();

        body.put(
                "model",
                model
        );

        /*
         * Kaynaklı web.
         */
        if (webSearch) {

            body.put(
                    "citation_options",
                    "enabled"
            );

            JSONObject tools =
                    new JSONObject();

            JSONArray enabledTools =
                    new JSONArray();

            enabledTools.put(
                    "web_search"
            );

            enabledTools.put(
                    "visit_website"
            );

            tools.put(
                    "enabled_tools",
                    enabledTools
            );

            JSONObject compoundCustom =
                    new JSONObject();

            compoundCustom.put(
                    "tools",
                    tools
            );

            body.put(
                    "compound_custom",
                    compoundCustom
            );
        }

        JSONArray messages =
                new JSONArray();

        JSONObject system =
                new JSONObject();

        system.put(
                "role",
                "system"
        );

        if (webSearch) {

            system.put(
                    "content",
                    "Sen GökAI adlı Türkçe yapay zekasın. " +
                            "Bu istek WEB MODUNDADIR. " +
                            "Güncel bilgiler için web araması kullan. " +
                            "Tahmin ederek eski bilgi verme. " +
                            "Cevabın sonunda kullandığın kaynakları açıkça belirt."
            );

        } else {

            system.put(
                    "content",
                    "Sen GökAI adlı Türkçe yapay zekasın. " +
                            "Doğal, açık ve faydalı cevap ver. " +
                            "Güncel olmayan bir konuda kesin güncelmiş gibi davranma."
            );
        }

        messages.put(system);

        JSONObject user =
                new JSONObject();

        user.put(
                "role",
                "user"
        );

        user.put(
                "content",
                text
        );

        messages.put(user);

        body.put(
                "messages",
                messages
        );

        JSONObject response =
                postGroq(
                        apiKey,
                        body
                );

        String answer =
                response
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString(
                                "content",
                                "Cevap alınamadı."
                        );

        clearPendingAttachment();

        sendAnswer(answer);
    }

    private void callVisionModel(
            String apiKey,
            String text
    ) throws Exception {

        JSONObject body =
                new JSONObject();

        body.put(
                "model",
                "qwen/qwen3.6-27b"
        );

        JSONArray messages =
                new JSONArray();

        JSONObject system =
                new JSONObject();

        system.put(
                "role",
                "system"
        );

        system.put(
                "content",
                "Sen GökAI adlı Türkçe yapay zekasın. " +
                        "Gönderilen görseli dikkatlice incele ve soruya göre cevap ver."
        );

        messages.put(system);

        JSONObject user =
                new JSONObject();

        user.put(
                "role",
                "user"
        );

        JSONArray content =
                new JSONArray();

        JSONObject textPart =
                new JSONObject();

        textPart.put(
                "type",
                "text"
        );

        if (text == null ||
                text.trim().isEmpty()) {

            text =
                    "Bu görseli ayrıntılı şekilde incele.";
        }

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

        messages.put(user);

        body.put(
                "messages",
                messages
        );

        JSONObject response =
                postGroq(
                        apiKey,
                        body
                );

        String answer =
                response
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString(
                                "content",
                                "Görsel incelenemedi."
                        );

        clearPendingAttachment();

        sendAnswer(answer);
    }

    private JSONObject postGroq(
            String apiKey,
            JSONObject body
    ) throws Exception {

        URL url =
                new URL(
                        "https://api.groq.com/openai/v1/chat/completions"
                );

        HttpURLConnection connection =
                (HttpURLConnection)
                        url.openConnection();

        connection.setRequestMethod(
                "POST"
        );

        connection.setRequestProperty(
                "Authorization",
                "Bearer " + apiKey
        );

        connection.setRequestProperty(
                "Content-Type",
                "application/json"
        );

        connection.setRequestProperty(
                "Groq-Model-Version",
                "latest"
        );

        connection.setConnectTimeout(
                20000
        );

        connection.setReadTimeout(
                90000
        );

        connection.setDoOutput(true);

        byte[] requestBytes =
                body.toString()
                        .getBytes("UTF-8");

        /*
         * İstek aşırı büyürse Groq'a göndermeden
         * burada durduruyoruz.
         */
        if (requestBytes.length >
                5 * 1024 * 1024) {

            throw new Exception(
                    "Gönderilen içerik fazla büyük. Dosyayı veya resmi küçültüp tekrar dene."
            );
        }

        OutputStream os =
                connection.getOutputStream();

        os.write(requestBytes);
        os.flush();
        os.close();

        int code =
                connection.getResponseCode();

        InputStream responseStream;

        if (code >= 200 &&
                code < 300) {

            responseStream =
                    connection.getInputStream();

        } else {

            responseStream =
                    connection.getErrorStream();
        }

        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                responseStream
                        )
                );

        StringBuilder result =
                new StringBuilder();

        String line;

        while ((line =
                reader.readLine()) != null) {

            result.append(line);
        }

        reader.close();
        connection.disconnect();

        if (code >= 200 &&
                code < 300) {

            return new JSONObject(
                    result.toString()
            );
        }

        if (code == 413) {

            throw new Exception(
                    "Web isteği fazla büyük olduğu için reddedildi. Yeni sohbette tekrar dene."
            );
        }

        throw new Exception(
                "API hatası " +
                        code +
                        ": " +
                        result
        );
    }

    private void clearPendingAttachment() {

        pendingImage = "";
        pendingImageMime = "";
        pendingFileText = "";
        pendingFileName = "";
    }

    private void sendAnswer(
            String answer
    ) {

        sendJs(
                "window.onAIResult(" +
                        JSONObject.quote(
                                answer
                        ) +
                        ");"
        );
    }

    private void sendJs(
            String javascript
    ) {

        runOnUiThread(() -> {

            if (webView != null) {

                webView.evaluateJavascript(
                        javascript,
                        null
                );
            }
        });
    }

    @Override
    protected void onDestroy() {

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }
    }
