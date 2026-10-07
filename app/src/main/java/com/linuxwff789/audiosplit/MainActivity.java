package com.linuxwff789.audiosplit;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-app audio output routing UI.
 *
 * Each row picks where that app's audio goes; apps left on "跟随系统" are untouched.
 * "保存并生效" pushes the rules into system_server (broadcast) and stores them for reboot.
 */
public class MainActivity extends Activity {

    private static final String[] DEVICE_VALUES = {"", "speaker", "a2dp", "wired", "usb", "ble"};
    private static final String[] DEVICE_LABELS = {
            "跟随系统", "手机扬声器", "蓝牙", "有线耳机", "USB", "BLE 耳机"};

    private final List<Entry> entries = new ArrayList<>();
    private AppAdapter adapter;
    private TextView status;
    private BroadcastReceiver statusReceiver;
    private long lastReplyAt;
    private String lastStatusText = "";
    private boolean showLog;

    private static final class Entry {
        String label;
        String pkg;
        String device = "";
        android.graphics.drawable.Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("AudioSplit " + BuildConfig.VERSION_NAME);
        title.setTextSize(18f);
        title.setPadding(dp(16), dp(16), dp(16), dp(4));
        root.addView(title);

        TextView devices = new TextView(this);
        devices.setTextSize(12f);
        devices.setPadding(dp(16), 0, dp(16), dp(8));
        devices.setText("已连接输出: " + connectedDevices());
        root.addView(devices);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(dp(8), 0, dp(8), dp(4));

        Button save = new Button(this);
        save.setText("保存并生效");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyRules();
            }
        });
        buttons.addView(save, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button ping = new Button(this);
        ping.setText("查询状态");
        ping.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppConfigStore.ping(MainActivity.this);
                status.setText("状态: 已发送查询,等待 system_server 回包…");
            }
        });
        buttons.addView(ping, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttons);

        LinearLayout buttons2 = new LinearLayout(this);
        buttons2.setOrientation(LinearLayout.HORIZONTAL);
        buttons2.setPadding(dp(8), 0, dp(8), dp(4));

        Button check = new Button(this);
        check.setText("权限自检");
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSelfCheck();
            }
        });
        buttons2.addView(check, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button logToggle = new Button(this);
        logToggle.setText("显示日志");
        logToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLog = !showLog;
                logToggle.setText(showLog ? "隐藏日志" : "显示日志");
                renderStatus();
            }
        });
        buttons2.addView(logToggle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons2);

        status = new TextView(this);
        status.setTextSize(11f);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        status.setText("状态: 等待 system_server 回包…(模块需已激活且重启过)");
        ScrollView statusScroll = new ScrollView(this);
        statusScroll.addView(status);
        root.addView(statusScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String text = intent.getStringExtra(Protocol.EXTRA_TEXT);
                if (text != null) {
                    lastReplyAt = System.currentTimeMillis();
                    lastStatusText = text;
                    renderStatus();
                }
            }
        };
        IntentFilter f = new IntentFilter(Protocol.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(statusReceiver, f);
        }

        loadApps();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                AppConfigStore.ping(MainActivity.this);
            }
        }, 800);
    }

    @Override
    protected void onDestroy() {
        if (statusReceiver != null) {
            unregisterReceiver(statusReceiver);
            statusReceiver = null;
        }
        super.onDestroy();
    }

    private void loadApps() {
        entries.clear();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);

        Map<String, String> savedRules = new HashMap<>();
        String saved = AppConfigStore.load(this);
        if (saved != null) {
            Config cfg = Config.parse(saved, "prefs");
            if (cfg != null) {
                for (Config.App a : cfg.apps) {
                    savedRules.put(a.pkg, a.device);
                }
            }
        }

        for (ResolveInfo ri : resolved) {
            if (ri.activityInfo == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) {
                continue;
            }
            Entry e = new Entry();
            e.pkg = pkg;
            e.label = String.valueOf(ri.loadLabel(pm));
            e.icon = ri.loadIcon(pm);
            String dev = savedRules.get(pkg);
            if (dev != null) {
                e.device = dev;
            }
            entries.add(e);
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        adapter.notifyDataSetChanged();
    }

    private String connectedDevices() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(d.getProductName()).append("(").append(typeName(type)).append(")");
        }
        return sb.length() == 0 ? "仅手机扬声器" : "手机扬声器, " + sb;
    }

    private static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "蓝牙A2DP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "蓝牙SCO";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "BLE耳机";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return "BLE音箱";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "有线耳麦";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "有线耳机";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB设备";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB耳机";
            default:
                return "type" + type;
        }
    }

    /** Status pane shows the self check only; the log tail is behind the toggle button. */
    private void renderStatus() {
        String text = lastStatusText;
        if (!showLog) {
            int split = text.indexOf("---- log ----");
            if (split > 0) {
                text = text.substring(0, split) + "(日志已折叠,点「显示日志」展开)";
            }
        }
        status.setText(text);
    }

    private static String permName(int state) {
        return state == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED";
    }

    /** Local permission/state audit, then ask system_server for its own report. */
    private void runSelfCheck() {
        StringBuilder sb = new StringBuilder();
        sb.append("app 侧自检\n");
        sb.append("QUERY_ALL_PACKAGES: ")
                .append(permName(checkSelfPermission("android.permission.QUERY_ALL_PACKAGES")))
                .append('\n');
        sb.append("RECEIVE_BOOT_COMPLETED: ")
                .append(permName(checkSelfPermission("android.permission.RECEIVE_BOOT_COMPLETED")))
                .append('\n');
        sb.append("可视应用数: ").append(entries.size()).append('\n');

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        sb.append("当前输出设备: ").append(connectedDevices()).append('\n');

        java.io.File f = AppConfigStore.externalFile(this);
        sb.append("配置文件: ").append(f == null ? "无外部目录"
                : (f.exists() ? f.getAbsolutePath() + " (" + f.length() + "B)"
                        : "尚未写入 " + f.getAbsolutePath())).append('\n');

        sb.append("规则数: ").append(AppConfigStore.load(this) == null ? 0 : ruleCount())
                .append('\n');
        if (lastReplyAt == 0) {
            sb.append("system_server: 从未回包 -> 模块未激活 / 需要重启\n");
        } else {
            sb.append("system_server: ").append((System.currentTimeMillis() - lastReplyAt) / 1000)
                    .append("s 前回过包\n");
        }
        sb.append("(am=").append(am == null ? "null" : "ok").append(")");
        lastStatusText = sb.toString();
        renderStatus();
        AppConfigStore.ping(this);
    }

    private int ruleCount() {
        Config cfg = Config.parse(AppConfigStore.load(this), "prefs");
        return cfg == null ? 0 : cfg.apps.size();
    }

    private void applyRules() {
        try {
            JSONArray apps = new JSONArray();
            int count = 0;
            for (Entry e : entries) {
                if (e.device == null || e.device.isEmpty()) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("pkg", e.pkg);
                o.put("device", e.device);
                apps.put(o);
                count++;
            }
            JSONObject root = new JSONObject();
            root.put("apps", apps);
            String json = root.toString();
            AppConfigStore.save(this, json);
            AppConfigStore.push(this, json);
            Toast.makeText(this, "已推送 " + count + " 条规则", Toast.LENGTH_SHORT).show();
            status.setText("已推送 " + count + " 条规则,等待回包…");
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    AppConfigStore.ping(MainActivity.this);
                }
            }, 700);
        } catch (Throwable t) {
            Log.e("apply rules failed", t);
            Toast.makeText(this, "生成配置失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Entry e = entries.get(position);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));

            ImageView icon = new ImageView(MainActivity.this);
            if (e.icon != null) {
                icon.setImageDrawable(e.icon);
            }
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(12), 0, dp(8), 0);
            TextView name = new TextView(MainActivity.this);
            name.setText(e.label);
            name.setTextSize(15f);
            TextView pkg = new TextView(MainActivity.this);
            pkg.setText(e.pkg);
            pkg.setTextSize(10f);
            pkg.setAlpha(0.6f);
            texts.addView(name);
            texts.addView(pkg);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Spinner spinner = new Spinner(MainActivity.this);
            ArrayAdapter<String> devAdapter = new ArrayAdapter<>(MainActivity.this,
                    android.R.layout.simple_spinner_item, DEVICE_LABELS);
            devAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(devAdapter);
            int selected = 0;
            for (int i = 0; i < DEVICE_VALUES.length; i++) {
                if (DEVICE_VALUES[i].equals(e.device)) {
                    selected = i;
                    break;
                }
            }
            spinner.setSelection(selected);
            final int rowPosition = position;
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    entries.get(rowPosition).device = DEVICE_VALUES[pos];
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            row.addView(spinner, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return row;
        }
    }
}
