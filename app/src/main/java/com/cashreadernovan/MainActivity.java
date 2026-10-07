package com.cashreadernovan;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(32,32,32,32);
        TextView title = new TextView(this);
        title.setText("Cash Reader novan");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        box.addView(title);
        TextView info = new TextView(this);
        info.setText("Aplikasi Android native siap dikembangkan.");
        box.addView(info);
        setContentView(box);
    }
}
