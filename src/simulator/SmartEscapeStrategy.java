package simulator;

import java.util.ArrayList;
import java.util.List;

// ═══════════════════════════════════════════════════════════════════════════
// SmartEscapeStrategy — 【智慧逃生系統】聯網全域感測器 + 雙階段避難
//   內容搬自原本 People.SmartEscape()，SMART / HYBRID 兩種情境共用同一套邏輯
//   (兩者差異在 Simulator 端是否啟用備援控制，跟這裡的決策邏輯無關)。
//   行為與呼叫順序(含所有 rng 消耗點)完全不變；原本 Simulator.stageAssignCount++
//   那兩行，現在在 People.instinctiveEscapeStep()/planNextAdvice() 內部改成呼叫
//   注入的 SimulationContext.onStageAssigned()，耦合的消除發生在 People 內部。
// ═══════════════════════════════════════════════════════════════════════════
class SmartEscapeStrategy implements EscapeStrategy {

    @Override
    public void step(People p, int currentTick) {
        p.accumulatedCO += p.space.building[p.z][p.y][p.x].smoke;
        p.checkStatus();
        if (p.isDead || p.isEscaped) return;

        // 尚未察覺異常(有系統：聞煙味/看到煙火 或 收到系統示警)，這個tick原地不動
        if (p.stillUnaware(currentTick, true)) return;

        p.device.tick(People.rng);
        p.panic.update(p.space.building[p.z][p.y][p.x].smoke);

        // ─── 撤回判定改用連通性檢查 ──────────────────────────
        // 「身上有任務」分兩種：targetStage(跨樓層，下個tick開頭直接執行跨越)，
        // 或 junctionTargetPos(同樓層任務，目標是下一個決策點，沿快取路徑走)。
        // 兩種都用同一套邏輯判斷是否撤回：目標本身著火→立即撤回；否則用目前已知的
        // 火/危險格跑一次連通性檢查，「目前位置→任務目標格」不再存在安全路徑才撤回。
        if (p.targetStage != null) {
            boolean fireHit = p.targetStage.fire;
            boolean lostConnectivity = false;
            if (!fireHit) {
                RouteFinder.HazardKnowledge hk = p.routeFinder.gatherKnownHazards(p.z, p.y, p.x, p.profile);
                lostConnectivity = !p.routeFinder.isReachable(p.z, p.y, p.x, p.targetStage.z, p.targetStage.y, p.targetStage.x,
                    hk.knownFires, hk.knownHazards);
            }
            if (fireHit || lostConnectivity) {
                // 【建議撤回】目標樓梯間著火，或已知資訊顯示這條路已經不通了，視為系統主動撤回這筆建議，
                // 下個tick重新規劃改道；記錄撤回時間，用於統計「撤回後多久才拿到新建議」
                if (p.kpi.adviceRevokedTick == null) p.kpi.adviceRevokedTick = currentTick;
                System.out.println("[ADVICE-REVOKE][SMART] tick=" + currentTick
                    + " id=" + p.id
                    + " reason=" + (fireHit ? "ENV_CHANGE" : "CONNECTIVITY_LOST")
                    + " issuedTick=" + p.kpi.adviceIssuedTick
                    + " pos=(" + p.z + "," + p.y + "," + p.x + ")"
                    + " selfSmoke=" + String.format("%.2f", p.space.building[p.z][p.y][p.x].smoke)
                    + " selfFire=" + p.space.building[p.z][p.y][p.x].fire
                    + " targetStage=(" + p.targetStage.z + "," + p.targetStage.y + "," + p.targetStage.x + ")"
                    + " targetSmoke=" + String.format("%.2f", p.targetStage.smoke)
                    + " rerouteAttempts(before)=" + p.kpi.rerouteAttempts);
                p.targetStage = null;
                p.kpi.adviceIssuedTick = null;
                p.kpi.rerouteAttempts++;
                p.kpi.everRerouted = true;
            } else {
                p.z = p.targetStage.z; p.y = p.targetStage.y; p.x = p.targetStage.x;
                p.targetStage = null;
                p.kpi.adviceIssuedTick = null; // 建議已成功執行完畢，生命週期結束
                p.checkStatus();
                if (p.isDead || p.isEscaped) return;
            }
        } else if (p.junctionTargetPos != null) {
            Obj junctionObj = p.space.building[p.junctionTargetPos[0]][p.junctionTargetPos[1]][p.junctionTargetPos[2]];
            boolean fireHit = junctionObj.fire;
            boolean safetyCapHit = p.kpi.adviceIssuedTick != null
                && (currentTick - p.kpi.adviceIssuedTick) > People.ADVICE_SAFETY_CAP_TICKS;
            boolean lostConnectivity = false;
            if (!fireHit && !safetyCapHit) {
                RouteFinder.HazardKnowledge hk = p.routeFinder.gatherKnownHazards(p.z, p.y, p.x, p.profile);
                lostConnectivity = !p.routeFinder.isReachable(p.z, p.y, p.x,
                    p.junctionTargetPos[0], p.junctionTargetPos[1], p.junctionTargetPos[2],
                    hk.knownFires, hk.knownHazards);
            }
            if (fireHit || lostConnectivity || safetyCapHit) {
                if (p.kpi.adviceRevokedTick == null) p.kpi.adviceRevokedTick = currentTick;
                System.out.println("[ADVICE-REVOKE][SMART] tick=" + currentTick
                    + " id=" + p.id
                    + " reason=" + (fireHit ? "ENV_CHANGE" : (safetyCapHit ? "SAFETY_CAP" : "CONNECTIVITY_LOST"))
                    + " issuedTick=" + p.kpi.adviceIssuedTick
                    + " pos=(" + p.z + "," + p.y + "," + p.x + ")"
                    + " selfSmoke=" + String.format("%.2f", p.space.building[p.z][p.y][p.x].smoke)
                    + " selfFire=" + p.space.building[p.z][p.y][p.x].fire
                    + " junctionTarget=(" + p.junctionTargetPos[0] + "," + p.junctionTargetPos[1] + "," + p.junctionTargetPos[2] + ")"
                    + " targetSmoke=" + String.format("%.2f", junctionObj.smoke)
                    + " rerouteAttempts(before)=" + p.kpi.rerouteAttempts);
                p.junctionTargetPos = null; p.junctionPath = null; p.junctionPathIdx = 0;
                p.kpi.adviceIssuedTick = null;
                p.kpi.rerouteAttempts++;
                p.kpi.everRerouted = true;
            }
            // 仍有效的話這個tick不特別處理，交給下面主迴圈沿快取路徑走
        }

        // 帶小孩：一開始要花幾個tick尋找/確認小孩安全
        if (p.childGatherDelay > 0) {
            p.childGatherDelay--;
            return;
        }

        // 行動不便者：可能選擇原地等待救援
        if (p.profile == PersonProfile.IMPAIRED) {
            p.updateWaitingForRescue();
            if (p.waitingForRescue) {
                p.markReportedTrapped(currentTick);
                return;
            }
        }

        // 同行者：同伴落後太多時，可能選擇等待或折返
        if (p.waitForCompanion(currentTick)) {
            p.markReportedTrapped(currentTick);
            return;
        }

        // 系統辨識與標記：只要仍連線，行動不便者/年長者這類需優先協助對象會被系統標記
        if (p.device.networkConnected && (p.profile == PersonProfile.IMPAIRED || p.profile == PersonProfile.ELDERLY)) {
            p.kpi.systemIdentifiedVulnerable = true;
        }

        // 恐慌：反應延遲，這個tick整個人愣住
        if (p.panic.rollFreeze(People.rng)) {
            return;
        }

        // 服從率 + 連線狀態：斷線、或恐慌下不服從建議時，退回使用直覺逃生邏輯
        boolean panicNonCompliance = p.panic.rollMisstep(People.rng);
        boolean usingSmartGuidance = p.device.networkConnected && !panicNonCompliance
            && People.rng.nextDouble() < People.COMPLIANCE_RATE;

        if (!usingSmartGuidance) {
            p.instinctiveEscapeStep(currentTick);
            return;
        }

        // 【只修測量、不改變行為】不強迫Smart在濃煙中放棄系統路徑規劃——
        // 系統的資訊來源是全域感測器網路，即使人正站在濃煙格裡，系統仍可能透過
        // 其他偵測器/已知火點資訊繼續規劃出相對安全的路線，所以繼續呼叫 computeSmartPath()。
        // 但為了不讓「持續待在同一段濃煙裡」被重複算成一次次新的「誤入危險路線」，
        // 用 inHazardNow 追蹤「目前是否處於濃煙中」，只有從「不在濃煙」轉為「進入濃煙」
        // 的那一瞬間才記一次；只要沒離開過濃煙，之後每一步都不會重複觸發。
        boolean inHazardNow = p.space.building[p.z][p.y][p.x].smoke > 0.7;

        // ─── 同樓層移動也是正式任務單位：沿快取好的junctionPath走，
        // 只有在「沒有任何進行中任務」或「剛走到任務目標格」時才重新呼叫 computeSmartPath() 規劃下一段。
        boolean moved = false;
        for (int step = 0; step < p.speed; step++) {
            if (p.targetStage != null) {
                // 上一輪迴圈(或這個tick最上面)剛指派了「下個tick跨樓層」的任務，
                // 這個tick先停在這裡，實際跨越動作留到下個tick開頭執行。
                moved = true;
                break;
            }

            if (p.junctionTargetPos == null) {
                boolean issued = p.planNextAdvice(currentTick);
                if (!issued) { if (!moved) p.markReportedTrapped(currentTick); break; }
                if (p.targetStage != null) { moved = true; break; } // 剛規劃出的任務是跨樓層，這個tick先停在這裡
            }

            int[] nextCell = p.junctionPath.get(p.junctionPathIdx);
            moved = true;
            p.z = nextCell[0]; p.y = nextCell[1]; p.x = nextCell[2];
            p.junctionPathIdx++;
            if (p.kpi.firstCorrectDecisionTick == null) p.kpi.firstCorrectDecisionTick = currentTick;
            p.checkStatus();
            if (p.isDead || p.isEscaped) break;

            boolean nowInSmoke = p.space.building[p.z][p.y][p.x].smoke > 0.7;
            if (nowInSmoke && !inHazardNow) {
                // 系統建議的路線，實際走到時才發現煙霧已經超標(感測器/資訊延遲)——
                // 只在「這一步剛好從乾淨格走進濃煙格」的瞬間記一次。
                p.kpi.everWrongRoute = true;
            }
            inHazardNow = nowInSmoke;

            if (p.z == p.junctionTargetPos[0] && p.y == p.junctionTargetPos[1] && p.x == p.junctionTargetPos[2]) {
                // 抵達這次任務的目標格(分岔點/本層樓梯間/出口)，任務結束；
                // 下一輪迴圈(若還有剩餘speed)會重新呼叫 planNextAdvice() 規劃下一段。
                p.junctionTargetPos = null; p.junctionPath = null; p.junctionPathIdx = 0;
                p.kpi.adviceIssuedTick = null;
            }
        }
        if (!moved) p.randomMove(p.speed);
    }
}
