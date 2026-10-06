package com.linuxwff789.audiosplit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String SAMPLE = "{\n"
            + "  \"apps\": [\n"
            + "    { \"pkg\": \"com.ss.android.ugc.aweme\", \"device\": \"speaker\" },\n"
            + "    { \"pkg\": \"com.tencent.qqmusic\", \"device\": \"a2dp\" }\n"
            + "  ]\n"
            + "}\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setText("AudioSplit " + BuildConfig.VERSION_NAME);
        root.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setPadding(0, pad, 0, pad);
        body.setText("作用域:android(system_server)\n\n"
                + "配置文件(root 写入,权限 644):\n"
                + Config.PATHS[0] + "\n"
                + "备用: " + Config.PATHS[1] + "\n\n"
                + "device 可选: speaker / a2dp / wired / usb / ble\n"
                + "未列出的应用继续走系统默认(蓝牙)。\n\n"
                + "切配置后无需重启:system_server 每 20 秒读一次;\n"
                + "生效情况见 su -c 'cat /data/system/audiosplit.log'\n\n"
                + "示例:\n" + SAMPLE);
        root.addView(body);

        Button copy = new Button(this);
        copy.setText("复制示例配置");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("audiosplit", SAMPLE));
                    Toast.makeText(MainActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        root.setGravity(Gravity.START);
        setContentView(scroll);
    }
}
