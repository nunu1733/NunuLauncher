package app.lawnchair.benchmark.target;

import android.app.Activity;
import android.os.Bundle;

/**
 * Issue #441: no-op launch target for the B1/B6 benchmark trials. The
 * benchmark never launches this activity; it only has to be installable and
 * resolvable as the package's launcher activity.
 */
public class TargetActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
