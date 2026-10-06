/*
 * Issue #526: the process-wide in-flight apply gate for the edit surface
 * (targetSdk 37 fork UI contract, spec 526). Pure display/operation-state
 * authority only — no write path, no DB, no Android framework dependency, so
 * the state machine is JVM-unit-testable (EditSurfaceApplyGateTest).
 */
package app.lawnchair.homeedit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Issue #526: 編集画面の適用進行中を示す単一権威の状態機械。
 *
 * `Idle → InFlight →（旧applyのterminal結果受領）→ Correlating（相関capture
 * 再取得中）→ Idle` の遷移のみを行う。terminal結果（Applied / stale /
 * rejected / error のいずれも）を受けたあとは、terminal後の相関captureが
 * 完了するまで [beginApply] を許さない（旧適用前のcaptureをstale図として
 * 編集→確定→再読込の往復を発生させない。spec 526の適用進行中再作成契約）。
 * 表示と操作の権威であり、write経路は持たない（適用・DB・recovery契約は
 * 既存のまま）。
 *
 * 純粋なクラスとしてAndroid依存を持たない。状態はCompose observable
 * （back gateの有効化と確定gateのfeedback表示がUIから読む）。
 */
class EditSurfaceApplyGate {

    /** 適用gateの状態（spec 526。遷移は下記3メソッドのみ）。 */
    enum class State {
        /** 適用なし。確定（beginApply）を許す。 */
        Idle,

        /** DB適用が進行中。確定を拒み、システムbackを握り潰す。 */
        InFlight,

        /**
         * 適用terminal後、相関capture再取得中。確定を拒む
         * （terminal前のcaptureをstale図として扱わないため）。
         */
        Correlating,
    }

    /** 現在の状態（UIのback gate・確定gateのfeedbackが読むobservable状態）。 */
    var state by mutableStateOf(State.Idle)
        private set

    /**
     * 確定の開始要求。Idle のみ InFlight へ遷移してtrue。InFlight /
     * Correlating では二重適用としてfalse（呼び出し側はbusy理由を表示する）。
     */
    fun beginApply(): Boolean {
        if (state != State.Idle) return false
        state = State.InFlight
        return true
    }

    /**
     * 適用のterminal結果の受領（Applied / stale / rejected / error のいずれも）。
     * InFlight → Correlating。他のstateからの呼び出しは防御的にno-op。
     */
    fun onApplyTerminal() {
        if (state == State.InFlight) state = State.Correlating
    }

    /**
     * Issue #526 revision（CI実測 #538）: 零書込みかつ世界不変のterminal
     * （writer busy / lock系rejected / ConcurrentRun / NoChanges等の防御到達）での
     * 解放。適用は起きずrecovery動作もないため画面のcaptureが現行のまま有効で、
     * 相関再取得は不要。世界が動していた場合でも、次のconfirmは既存の
     * STALE_REVISION / EXACT_PRECONDITION_FAILED gateが零書込みで止める
     * （fail-closedは既存層が担う）。Correlating → Idle（InFlightからの呼び出しも
     * 防御的にIdleへ。Idleは不変）。
     */
    fun onTerminalWithoutWorldChange() {
        when (state) {
            State.InFlight, State.Correlating -> state = State.Idle
            State.Idle -> Unit
        }
    }

    /**
     * capture 1回の完了の通知（初回読込・stale時の開き直し・rollback/recovery系
     * terminal後の相関再取得を含む全reloadCapture完了から呼ばれる）。
     * Correlating → Idle（terminal後の最初の完了captureだけが解除する。
     * terminal前に完了したcaptureは旧適用前の図であり得るため解除しない）。
     * IdleはIdleのまま。
     */
    fun onCaptureReady() {
        if (state == State.Correlating) state = State.Idle
    }
}

/**
 * Issue #526: process-wideな単一instance。全wrapper・全Activityがこの同一identity
 * を参照する（wrapperのfieldへの保持は禁止 — `HomeEditSurfaceAccess.get(context)`
 * は呼び出しごとに新しいwrapperを返す。Activity/wrapperごとのコピーも禁止）。
 * 適用とcaptureの実体がprocess内の単一module instanceに属するのと同じ単位で、
 * in-flight適用の操作状態もprocess内で1つであることを示す。
 */
internal val editSurfaceApplyGate = EditSurfaceApplyGate()
