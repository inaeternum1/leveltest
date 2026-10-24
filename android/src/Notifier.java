package __PKG__;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

// 학생 앱 알림. 서버(유료 푸시 서비스) 없이 앱이 15분마다 선생님 알림 설정을 확인한다.
// - 매일 알림: 선생님이 정한 시각(한국 시간)이 되면 하루 한 번
// - 지금 보내기: 선생님이 채점 화면에서 보낸 알림을 다음 확인 때(보통 15분 안) 한 번
public class Notifier extends BroadcastReceiver {
    static final boolean ENABLED = __NOTIFY__;
    static final String API = "https://inulbquzdxzinpidgwhh.supabase.co/functions/v1/api/notice";
    static final String CH = "lt_daily";
    static final long TICK = 15 * 60 * 1000L;
    static final TimeZone KST = TimeZone.getTimeZone("Asia/Seoul");

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("notify", Context.MODE_PRIVATE); }

    // delayMs 뒤에 한 번 깨운다(깨어나면 다시 다음 확인을 예약한다).
    static void schedule(Context c, long delayMs) {
        if (!ENABLED) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, Notifier.class).setAction("lt.TICK");
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long at = SystemClock.elapsedRealtime() + Math.max(30_000L, delayMs);
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        else am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
    }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "오늘의 문제 알림", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("새 문제와 선생님 알림");
            nm.createNotificationChannel(ch);
        }
    }

    static void show(Context c, int id, String title, String body) {
        channel(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        try {
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, b.build());
        } catch (SecurityException e) { /* 알림 권한이 꺼져 있음 */ }
    }

    static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(KST);
        return f.format(new Date());
    }

    static int minutesNow() {
        Calendar k = Calendar.getInstance(KST);
        return k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
    }

    static int parseHm(String hm) {
        try { String[] p = hm.split(":"); return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]); }
        catch (Exception e) { return -1; }
    }

    static JSONObject fetch() {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(API).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(10000);
            if (h.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    // 알림을 띄울지 판단하고 다음 확인 시각을 예약한다. (네트워크를 쓰므로 백그라운드 스레드에서 부른다)
    static void check(Context c) {
        SharedPreferences sp = prefs(c);
        JSONObject j = fetch();
        if (j != null) sp.edit().putString("cfg", j.toString()).apply();
        else { try { j = new JSONObject(sp.getString("cfg", "{}")); } catch (Exception e) { j = new JSONObject(); } }
        long next = TICK;

        // 1) 선생님이 '지금 보내기' 한 알림: 6시간 안의 것만 한 번
        JSONObject push = j.optJSONObject("push");
        if (push != null) {
            long id = push.optLong("id", 0);
            long at = push.optLong("at", 0);
            if (id > 0 && id != sp.getLong("pushId", 0) && System.currentTimeMillis() - at < 6 * 3600 * 1000L) {
                sp.edit().putLong("pushId", id).apply();
                show(c, 200, push.optString("title", "오늘의 레벨 테스트"), push.optString("body", ""));
            }
        }

        // 2) 매일 알림: 정한 시각부터 6시간 안에 하루 한 번
        JSONObject daily = j.optJSONObject("daily");
        if (daily != null && daily.optBoolean("on", false)) {
            int t = parseHm(daily.optString("time", ""));
            int now = minutesNow();
            String d = today();
            if (t >= 0) {
                if (now >= t && now < t + 360 && !d.equals(sp.getString("dailyDay", ""))) {
                    sp.edit().putString("dailyDay", d).apply();
                    show(c, 100, daily.optString("title", "오늘의 레벨 테스트"), daily.optString("body", "오늘 문제가 올라왔어요."));
                }
                // 정한 시각이 15분 안이면 그 시각에 맞춰 깨운다
                int wait = now < t ? t - now : t + 1440 - now;
                if (wait * 60_000L < next) next = Math.max(30_000L, wait * 60_000L + 20_000L);
            }
        }
        schedule(c, next);
    }

    @Override public void onReceive(final Context c, Intent intent) {
        if (!ENABLED) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try { check(c.getApplicationContext()); }
            catch (Exception e) { schedule(c, TICK); }
            finally { pr.finish(); }
        }).start();
    }
}
