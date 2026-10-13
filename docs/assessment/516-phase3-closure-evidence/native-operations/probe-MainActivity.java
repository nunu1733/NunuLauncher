package dev.nunu.phase3.installprobe;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Phase3 install probe");
        label.setGravity(Gravity.CENTER);
        setContentView(label);
    }
}
