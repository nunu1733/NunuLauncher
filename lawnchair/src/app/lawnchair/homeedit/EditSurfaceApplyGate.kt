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
 * rejected / error のいずれも）を受けたあとは、terminal後に開始された相関
 * captureが完了するまで [beginApply] を許さない（旧適用前のcaptureをstale図と
 * して編集→確定→再読込の往復を発生させない。spec 526の適用進行中再作成契約）。
 *
 * 解除の相関は世代ticketで固定する（review round 1）: terminalのたびに単調増加
 * する相関世代を進めて待ち世代として記録し、captureは開始時に [newCaptureTicket]
 * で世代を固定する。完了通知（[onCaptureReady]）はticketが待ち相関世代と一致する
 * 場合 — すなわちterminal後に開始されたcaptureの完了 — だけ Correlating を解除
 * する。terminal前に開始されたcapture（再作成直後の初回読込がin-flight適用と
 * 競合する場合を含む）や破棄済みinstanceのcapture（呼び出し側で排除する）では
 * 解除しない。1 terminal generation = 1 correlated reload。表示と操作の権威で
 * あり、write経路は持たない（適用・DB・recovery契約は既存のまま）。
 *
 * Issue #526 review round 2: 「1 terminal generation = 1 correlated reload」は
 * 解除条件だけでなく起動側でも保証する。相関reloadの起動権は
 * [claimCorrelatedCapture] の単一claimに一元化し、liveな表面だけがclaimして
 * 起動する（旧instanceのterminal直接経路と、再作成先live instanceのCorrelating
 * 観測経路の双方が同じclaimを通る）。destroy済み表面はclaimせず相関を未claimの
 * まま残す — 起動権をliveな再作成先へ任せる所有規約。
 *
 * 純粋なクラスとしてAndroid依存を持たない。状態はCompose observable
 * （back gateの有効化と確定gateのfeedback表示がUIから読む）。全状態変更
 * （claimを含む）はmain threadからの単独呼び出しを契約とする（ActivityのUI
 * event・handleApplyResult・Compose効果・instrumentation oracleの
 * runOnMainSyncはすべてmainで動く。JVM testは単スレッドでこの契約を模す）。
 */
class EditSurfaceApplyGate {

    /** 適用gateの状態（spec 526。遷移は下記のメソッドのみ）。 */
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
     * Issue #526 review round 1: 相関captureの世代（単調増加）。terminal
     * （InFlight → Correlating）のたびにだけ進む。captureは開始時点の世代を
     * ticketとして固定し、完了通知はそのticketで行う。
     */
    private var correlationGeneration = 0

    /**
     * 現在のterminalが待っている相関世代（Correlatingの間のみ参照される。
     * Idleでは不使用）。
     */
    private var pendingCorrelationGeneration = -1

    /**
     * Issue #526 review round 2: 相関reloadの起動権をclaim済みの相関世代。
     * [claimCorrelatedCapture] が成功するたびに待ち相関世代を記録し、同一世代への
     * 2回目のclaimをnullにする。次のterminalで待ち相関世代が進めば自然に無効に
     * なる（明示的なclearは持たない。stale ticketの完了やcapture失敗
     * （fail-closedのnull完了）でも再オープンしない — 起動権の単一化と解除条件は
     * 別契約で、解除はclaim済みでも [onCaptureReady] の既存規約どおり行われる）。
     */
    private var claimedCorrelationGeneration: Int? = null

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
     * InFlight → Correlating と同時に相関世代を進めて待ち世代として記録する。
     * 他のstateからの呼び出しは防御的no-op（相関世代も進めない）。
     */
    fun onApplyTerminal() {
        if (state == State.InFlight) {
            state = State.Correlating
            pendingCorrelationGeneration = ++correlationGeneration
        }
    }

    /**
     * Issue #526 review round 2: pending相関のreload起動権を1回だけclaimする。
     * Correlating のときだけ成功し、待ち相関世代（[onCaptureReady] へそのまま
     * 渡すticket。 [newCaptureTicket] と同値）を返す。同一世代への2回目の呼び出しは
     * null — terminal直接経路（旧instanceのhandleApplyResult）とCorrelating観測経路
     * （live instanceのCompose観測）の双方の起動がこのclaimを通るため、相関reload
     * （full capture）は1 terminal generationにつき高々1回になる。Idle / InFlight
     * でもnull（claimできるpending相関がない）。
     *
     * claimの消費は相関世代の比較のみで管理する: 待ち相関世代が次のterminalで進む
     * まで再claimはできない。stale ticketの完了やcapture失敗でもclaimを再オープン
     * しない — 自動起動の再試行権はclaimに持たせない。gateの解除自体はclaimと独立
     * で、次に開始されたcapture（ticketが待ち世代と一致）の完了 [onCaptureReady]
     * で既存どおり行われる（再作成先の初回読込やtestの既存再capture経路もこれで
     * 解除できる。claimは起動権の単一化のみを担う）。
     */
    fun claimCorrelatedCapture(): Int? {
        if (state != State.Correlating) return null
        val pending = pendingCorrelationGeneration
        if (claimedCorrelationGeneration == pending) return null
        claimedCorrelationGeneration = pending
        return pending
    }

    /**
     * Issue #526 review round 1（命名を規約へ同期）: 当該applyが零書込みかつ
     * local recoveryなしのterminal（writer busy / lock系rejected / ConcurrentRun /
     * NoChanges等の防御到達）での解放。適用もrecovery動作も起きないため画面側に
     * 追加の復旧導線は不要だが、共有layoutが外側で動いていない保証はしない —
     * 動いていた場合の保護は次のconfirmの既存STALE_REVISION /
     * EXACT_PRECONDITION_FAILED gateが零書込みで止める（fail-closedは既存層が
     * 担う。spec 526のoracle (3b)）。Correlating → Idle（InFlightからの呼び出しも
     * 防御的にIdleへ。Idleは不変）。
     */
    fun onTerminalWithoutLocalRecovery() {
        when (state) {
            State.InFlight, State.Correlating -> state = State.Idle
            State.Idle -> Unit
        }
    }

    /**
     * Issue #526 review round 1: これから開始するcapture用の相関世代ticket。
     * capture開始の呼び出しスレッド上で取得し、完了通知にそのまま渡す
     * （executor上での取得は開始順が入れ替わり得るため渡さない）。
     */
    fun newCaptureTicket(): Int = correlationGeneration

    /**
     * capture 1回の完了の通知（初回読込・stale時の開き直し・rollback/recovery系
     * terminal後の相関再取得を含む全reloadCapture完了から、開始時のticket付きで
     * 呼ばれる）。ticketが待ち相関世代と一致する（= terminal後に開始された）
     * 完了だけが Correlating → Idle を許される。terminal前に開始されたcaptureや
     * 世代のずれたticketでは解除しない。IdleはIdleのまま。
     */
    fun onCaptureReady(ticket: Int) {
        if (state == State.Correlating && ticket == pendingCorrelationGeneration) {
            state = State.Idle
        }
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
