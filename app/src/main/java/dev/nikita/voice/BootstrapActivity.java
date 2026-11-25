package dev.nikita.voice;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BootstrapActivity extends Activity {
    private enum Phase { IDLE, WORKING, READY, FAILED }
    private static volatile Phase phase = Phase.IDLE;
    private static volatile int percent = -1;
    private static volatile String problem = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressBar bar;
    private TextView heading, detail;
    private Button retry;
    private boolean launched;

    private static synchronized void prepare(Context app) {
        if (phase == Phase.WORKING || phase == Phase.READY) return;
        percent = -1;
        problem = "";
        phase = Phase.WORKING;
        new Thread(() -> {
            try (ZipFile apk = new ZipFile(app.getApplicationInfo().sourceDir)) {
                File files = app.getExternalFilesDir(null);
                if (files == null) throw new IOException("Хранилище телефона недоступно.");
                ZipEntry indexEntry = apk.getEntry(EmbeddedModelFiles.BASE + EmbeddedModelFiles.INDEX);
                if (indexEntry == null) throw new IOException("Не найден индекс моделей внутри приложения.");
                byte[] index = EmbeddedModelFiles.readSmall(apk.getInputStream(indexEntry));
                JSONObject json = new JSONObject(new String(index, StandardCharsets.UTF_8));
                if (json.getInt("version") != 1) throw new IOException("Неизвестная версия моделей.");
                JSONArray items = json.getJSONArray("files");
                List<EmbeddedModelFiles.Model> plan = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject m = items.getJSONObject(i);
                    plan.add(new EmbeddedModelFiles.Model(m.getString("path"), m.getLong("bytes"), m.getString("sha256")));
                }
                ZipEntry recipeEntry = apk.getEntry("decoder-beam.json");
                if (recipeEntry == null) throw new IOException("Не найдена схема подготовки декодера.");
                JSONObject recipe = new JSONObject(new String(
                        EmbeddedModelFiles.readSmall(apk.getInputStream(recipeEntry)), StandardCharsets.UTF_8));
                EmbeddedModelFiles.Model cache = null;
                for (EmbeddedModelFiles.Model m : plan) {
                    if (m.path.equals("nllb/decoder.onnx") && m.size == recipe.getLong("source_bytes")
                            && m.digest.equals(recipe.getString("source_sha256"))) {
                        String expectedHash = recipe.getString("sha256");
                        cache = new EmbeddedModelFiles.Model("nllb/decoder-" + expectedHash.substring(0, 16) + ".onnx",
                                recipe.getLong("bytes"), expectedHash);
                        break;
                    }
                }
                EmbeddedModelFiles.install(apk, index, plan, new File(files, "models"),
                        new File(app.getFilesDir(), "verified-models.properties"), cache,
                        (done, total) -> percent = (int) (100 * done / total));
                phase = Phase.READY;
            } catch (Exception e) {
                String msg = e.getLocalizedMessage();
                if (msg != null && msg.contains("ENOSPC")) msg = "На телефоне закончилось свободное место.";
                problem = msg == null ? "Не удалось прочитать или сохранить модели." : msg;
                phase = Phase.FAILED;
            }
        }, "ShonaVoice-Models").start();
    }

    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + 0.5f); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!isTaskRoot() && Intent.ACTION_MAIN.equals(getIntent().getAction()) &&
                getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish();
            return;
        }
        int paper = Color.rgb(251, 247, 239);
        getWindow().setStatusBarColor(paper);
        getWindow().setNavigationBarColor(paper);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(28), dp(20), dp(28), dp(20));
        body.setBackgroundColor(paper);
        heading = new TextView(this);
        heading.setTypeface(Typeface.SERIF);
        heading.setTextColor(Color.rgb(45, 43, 36));
        heading.setTextSize(31);
        heading.setText("Подготовка переводчика");
        body.addView(heading);
        detail = new TextView(this);
        detail.setTextSize(16);
        detail.setTextColor(Color.rgb(66, 60, 51));
        detail.setPadding(0, dp(12), 0, dp(20));
        body.addView(detail);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        body.addView(bar, new LinearLayout.LayoutParams(-1, dp(8)));
        retry = new Button(this);
        retry.setText("Повторить");
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> {
            prepare(getApplicationContext());
            refresh.run();
        });
        body.addView(retry, new LinearLayout.LayoutParams(-2, -2));
        setContentView(body);
        prepare(getApplicationContext());
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed() || launched) return;
            Phase state = phase;
            if (state == Phase.READY) {
                launched = true;
                Intent intent = new Intent().setClassName(BootstrapActivity.this, "dev.nikita.voice.MainActivity");
                startActivity(intent);
                finish();
                return;
            }
            if (state == Phase.FAILED) {
                heading.setText("Подготовка прервана");
                detail.setText(problem);
                bar.setVisibility(View.GONE);
                retry.setVisibility(View.VISIBLE);
                return;
            }
            int p = percent;
            heading.setText("Подготовка переводчика");
            detail.setText(p < 0 ? "Проверяем модели на устройстве…" :
                    "Сохраняем встроенные модели для работы без интернета. " + p + "%");
            bar.setVisibility(View.VISIBLE);
            bar.setIndeterminate(p < 0);
            if (p >= 0) bar.setProgress(p);
            retry.setVisibility(View.GONE);
            handler.postDelayed(this, 220);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        refresh.run();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
