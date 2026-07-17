package simulator;

// ─── 起火原因 ─────────────────────────────────────────────────
// 除了火/煙擴散速度差異(見EnvironmentSimulator)，每種起火原因還帶一個
// smokeToleranceFactor：人員對煙霧的「有效耐受倍率」，<1.0代表這種起火原因的
// 煙霧毒性較高，等同下修角色卡原本的CO致命閾值(見People.effectiveCoThreshold())；
// 1.0代表維持角色卡原本設定的閾值，不額外加成也不額外懲罰。
//
// 【降低耦合】原本這個列舉巢狀宣告在 Simulator 內部(Simulator.FireCause)，
// People/EnvironmentSimulator 都得認得 Simulator 這個類別才能用到它。
// 移成頂層列舉後，各處只需要 import/使用 FireCause 本身。
enum FireCause {
    ELECTRICAL(1.00), // 電線走火：初期火勢蔓延極快，但起初煙霧較少；煙霧毒性維持一般水準
    CHEMICAL(0.55),   // 化學起火：化學煙霧蔓延極快，且毒性遠高於一般燃燒煙霧，致死率最高
    ARSON(0.80),      // 縱火：使用了助燃劑，火跟煙都很快，煙霧毒性也偏高
    ACCIDENTAL(1.00); // 一般意外(預設)：正常的擴散速度，煙霧毒性維持一般水準

    final double smokeToleranceFactor;

    FireCause(double smokeToleranceFactor) {
        this.smokeToleranceFactor = smokeToleranceFactor;
    }
}
