package simulator;

// ═══════════════════════════════════════════════════════════════════════════
// DefaultEscapeStrategy — 【傳統逃生邏輯】沒有智慧系統，純靠個人直覺逃生
//   內容搬自原本 People.escape()，行為與呼叫順序(含所有 rng 消耗點)完全不變。
//   原本 Simulator.stageAssignCount++ 那一行，現在在 People.instinctiveEscapeStep()
//   內部改成呼叫注入的 SimulationContext.onStageAssigned()，這裡只是照原樣呼叫
//   p.instinctiveEscapeStep(currentTick)，耦合的消除發生在 People 內部。
// ═══════════════════════════════════════════════════════════════════════════
class DefaultEscapeStrategy implements EscapeStrategy {

    @Override
    public void step(People p, int currentTick) {
        p.accumulatedCO += p.space.building[p.z][p.y][p.x].smoke;
        p.checkStatus();
        if (p.isDead || p.isEscaped) return;

        // 尚未察覺異常(無系統：靠聞煙味/看到煙火)，這個tick原地不動
        if (p.stillUnaware(currentTick, false)) return;

        p.device.tick(People.rng);
        p.panic.update(p.space.building[p.z][p.y][p.x].smoke);

        if (p.targetStage != null) {
            if (p.targetStage.fire) {
                // 原本規劃要走的樓梯間已經失火，原路線失效，這個tick先取消目標，等下個tick重新規劃改道
                System.out.println("[REROUTE] tick=" + currentTick
                    + " id=" + p.id
                    + " pos=(" + p.z + "," + p.y + "," + p.x + ")"
                    + " selfSmoke=" + String.format("%.2f", p.space.building[p.z][p.y][p.x].smoke)
                    + " selfFire=" + p.space.building[p.z][p.y][p.x].fire
                    + " targetStage=(" + p.targetStage.z + "," + p.targetStage.y + "," + p.targetStage.x + ")"
                    + " targetSmoke=" + String.format("%.2f", p.targetStage.smoke)
                    + " rerouteAttempts(before)=" + p.kpi.rerouteAttempts);
                p.targetStage = null;
                p.kpi.rerouteAttempts++;
                p.kpi.everRerouted = true;
            } else {
                p.z = p.targetStage.z; p.y = p.targetStage.y; p.x = p.targetStage.x;
                p.targetStage = null;
                p.checkStatus();
                if (p.isDead || p.isEscaped) return;
            }
        }

        // 帶小孩：一開始要花幾個tick尋找/確認小孩安全，這段期間不會主動逃生
        if (p.childGatherDelay > 0) {
            p.childGatherDelay--;
            return;
        }

        // 行動不便者：可能選擇原地等待救援，而不是自行冒險移動
        if (p.profile == PersonProfile.IMPAIRED) {
            p.updateWaitingForRescue();
            if (p.waitingForRescue) {
                p.markReportedTrapped(currentTick);
                return;
            }
        }

        // 同行者：同伴落後太多時，可能選擇等待或折返確認同伴狀況
        if (p.waitForCompanion(currentTick)) {
            p.markReportedTrapped(currentTick);
            return;
        }

        // 恐慌：反應延遲，這個tick可能整個人愣住沒有行動
        if (p.panic.rollFreeze(People.rng)) {
            return;
        }

        p.instinctiveEscapeStep(currentTick);
    }
}
