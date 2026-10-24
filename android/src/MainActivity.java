package __PKG__;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

// 사이트를 그대로 띄우는 얇은 앱. 화면·기능 수정은 사이트에서 하므로 앱은 거의 다시 만들 필요가 없다.
public class MainActivity extends Activity {
    static final String HOST = "__HOST__";
    static final String START = "__START__";
    static final boolean RELOAD_ON_RESUME = __RELOAD__;
    static final boolean NOTIFY = __NOTIFY__;
    static final String NOTIFY_PERM = "android.permission.POST_NOTIFICATIONS";
    boolean testAfterGrant = false;
    WebView web;
    long pausedAt = 0;
    TextToSpeech tts;
    boolean ttsReady = false;

    // 웹 화면에서 부르는 기능: 카톡 등으로 공유, 복사
    public class Bridge {
        @JavascriptInterface public void share(final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "보내기"));
            });
        }
        // 영어 듣기: [{t: 문장, p: 높낮이}, ...] 를 차례로 읽는다. 줄 사이에 0.6초 쉰다.
        @JavascriptInterface public void speak(final String json, final float rate) {
            runOnUiThread(() -> {
                if (tts == null || !ttsReady) {
                    Toast.makeText(MainActivity.this, "음성 준비 중이에요. 잠시 후 다시 눌러 주세요.", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    JSONArray a = new JSONArray(json);
                    tts.stop();
                    tts.setSpeechRate(Math.max(0.3f, Math.min(1.5f, rate)));
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        tts.setPitch((float) o.optDouble("p", 1.0));
                        tts.speak(o.optString("t", ""), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "lt" + i);
                        if (i < a.length() - 1) tts.playSilentUtterance(600, TextToSpeech.QUEUE_ADD, "gap" + i);
                    }
                    tts.setPitch(1.0f);
                } catch (Exception e) { }
            });
        }
        @JavascriptInterface public void stopSpeak() {
            runOnUiThread(() -> { if (tts != null) tts.stop(); });
        }
        // 학생 앱 알림: 웹 화면에서 '알림 테스트'를 누르면 바로 하나 띄운다. 권한이 없으면 먼저 묻는다.
        @JavascriptInterface public boolean canNotify() { return NOTIFY; }
        // 학생 이름·학년을 알려 두면 그 학생에게만 보낸 알림도 받는다
        @JavascriptInterface public void setIdentity(String name, int grade) {
            if (!NOTIFY || name == null) return;
            Notifier.prefs(MainActivity.this).edit().putString("name", name.trim()).putInt("grade", grade).apply();
        }
        @JavascriptInterface public void testNotify() {
            runOnUiThread(() -> {
                if (!NOTIFY) return;
                if (needPerm()) { testAfterGrant = true; requestPermissions(new String[]{NOTIFY_PERM}, 7); return; }
                Notifier.show(MainActivity.this, 300, "알림 테스트", "알림이 잘 와요! 매일 정해진 시간에 오늘의 문제를 알려 줄게요.");
            });
        }
        @JavascriptInterface public void copy(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("link", text));
                Toast.makeText(MainActivity.this, "복사했어요", Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Bridge(), "LTApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
                return true;
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    v.loadDataWithBaseURL(null,
                        "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<body style='font-family:sans-serif;padding:32px 20px;text-align:center'>"
                        + "<h2>인터넷 연결을 확인해 주세요</h2>"
                        + "<button style='font-size:18px;padding:14px 28px;border-radius:12px' "
                        + "onclick=\"location.href='" + START + "'\">다시 시도</button></body>",
                        "text/html", "utf-8", null);
                }
            }
        });
        setContentView(web);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.US);
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                if (!ttsReady) {
                    Toast.makeText(MainActivity.this, "영어 음성이 설치돼 있지 않아요. 설정 > 텍스트 음성 변환에서 영어를 받아 주세요.", Toast.LENGTH_LONG).show();
                }
            }
        });
        if (state != null) web.restoreState(state); else web.loadUrl(START);
        if (NOTIFY) {
            if (needPerm()) requestPermissions(new String[]{NOTIFY_PERM}, 7);
            Notifier.schedule(this, 30_000L);
        }
    }

    boolean needPerm() { return Build.VERSION.SDK_INT >= 33 && checkSelfPermission(NOTIFY_PERM) != PackageManager.PERMISSION_GRANTED; }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 7) return;
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (ok && testAfterGrant) Notifier.show(this, 300, "알림 테스트", "알림이 잘 와요! 매일 정해진 시간에 오늘의 문제를 알려 줄게요.");
        if (!ok) Toast.makeText(this, "알림이 꺼져 있어요. 설정 > 애플리케이션 > 오늘의 레벨 테스트 > 알림에서 켤 수 있어요.", Toast.LENGTH_LONG).show();
        testAfterGrant = false;
    }

    // 앱을 닫으면 1분 뒤 알림을 한 번 확인한다(선생님이 방금 보낸 알림이 빨리 오도록)
    @Override protected void onPause() { super.onPause(); pausedAt = SystemClock.elapsedRealtime(); if (tts != null) tts.stop(); if (NOTIFY) Notifier.schedule(this, 60_000L); }

    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }

    // 학생 앱: 20분 넘게 있다가 다시 열면 새 문제를 보여 주려고 첫 화면을 다시 불러온다.
    // 문제를 푸는 중이면(.q 화면) 답이 지워지지 않게 그대로 둔다.
    @Override protected void onResume() {
        super.onResume();
        if (RELOAD_ON_RESUME && pausedAt > 0 && SystemClock.elapsedRealtime() - pausedAt > 20 * 60 * 1000L) {
            web.evaluateJavascript("document.querySelector('.q')?'1':'0'", v -> {
                if (v != null && v.contains("0")) web.loadUrl(START);
            });
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
