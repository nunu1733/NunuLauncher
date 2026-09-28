package app.lawnchair.fixture

import android.app.Activity
import android.os.Bundle

/**
 * Issue #441 A-10: no-op launch target shared by the benchmark fixture
 * activity-aliases. The aliases give the editing-burden benchmark fixture
 * distinct launch identities (component + label + icon) without depending on
 * real third-party packages or touching the product manifest. The activity is
 * never started by the seeding test; it only has to exist so the seeded
 * components resolve while the test APK is installed.
 */
class BenchmarkFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
