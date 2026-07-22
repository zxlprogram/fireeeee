package simulator;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

// ─── 主模擬 ───────────────────────────────────────────────────
// 【拆解God Class】重構前，這個類別本身超過1300行，把地圖產生、場景初始化、
// 火煙擴散演算法、地圖列印、逐tick主迴圈、統計彙整、報表列印全部混在一起，
// 而且幾乎所有欄位都是 static，任何子任務都直接讀寫這些全域狀態。
//
// 重構後，Simulator 變成「協調者(orchestrator)」：地圖生成交給 BuildingGenerator，
// 火煙擴散交給 EnvironmentSimulator，場景初始化交給 ScenarioInitializer，地圖列印
// 交給 ConsoleMapPrinter，統計彙整/報表交給 ModeStats/SimResult/ReportGenerator。
// 這些被拆出去的類別都是「明確接收參數、回傳結果」，不再反過來直接碰觸 Simulator
// 的 static 欄位，Simulator 只負責在正確的時機、用正確的順序呼叫它們。
//
// 【降低耦合】Simulator 實作 SimulationContext，把自己內部狀態透過一組窄介面
// (getDetectors/isSystemAlertTriggered/findPersonById/onStageAssigned/
// smokeToleranceFactor)安全地暴露給 People/RouteFinder，取代原本它們直接認得
// Simulator 這個具體類別、直接讀寫其 static 欄位的反向耦合寫法。
//
// 【策略模式取代mode的if/else】People 建構時就依當次模擬的 SimMode 決定好要用
// DefaultEscapeStrategy 還是 SmartEscapeStrategy，run() 主迴圈因此只需要對每個人
// 統一呼叫 p.tick(tick)，不再需要「if (useSmart) ... else ...」這種每個tick、
// 每個人都要重新判斷一次的分支。
public class Simulator implements SimulationContext {

    static Space space;
    static int surviveTime = 50000; // 活過5000時刻就算被救活，之後要改成滅火行動
    static List<People> peopleList = new ArrayList<>();
    static List<Detector> detectors = new ArrayList<>();
    // peopleList 會隨著死亡/逃脫被移除，所以另外保留一份不會被清空的登記表，
    // 讓「同行者」邏輯在同伴已經離開 peopleList 之後，仍能查到對方最後的狀態(存活/死亡/逃脫)
    static Map<Integer, People> allPeopleById = new HashMap<>();
    static int tick = 0;
    static FireCause currentCause = FireCause.ACCIDENTAL;
    static Random random = new Random();

    static long TotalPeopleSum = 0; // 跨迭代的總人數累計

    // ─── Session JSON 匯出 ─────────────────────────────
    // 每次 runOneSimulation() 都會重新建立一個 SessionExporter，記錄該場模擬(單一
    // 建築+單一起火點+單一模式)的靜態地圖、逐tick快照、關鍵事件，並在 run() 結束時
    // 匯出成 JSON 檔。因為檔名固定(依mode區分)，只會保留「最後一次」跑該 mode 的那份紀錄。
    static SessionExporter sessionExporter;
    static String sessionJsonFilename = "session_export.json";

    // ─── 改道成功率除錯用計數器 ───────────────────────────
    // 每次 run() 開始時歸零。記錄本場模擬中「有多少次被指派跨樓層目標(Stage)」，
    // 這是「有沒有機會觸發改道」的前提；如果這個數字是0，代表根本沒人跨樓層移動，
    // rerouteAttemptCount=0 是理所當然的，不是bug。
    static int stageAssignCount = 0;

    // ─── B2情境專用：備援控制是否啟用、以及本場模擬觸發了幾次主動控制動作 ───
    static boolean activeControlEnabled = false; // 只有 HYBRID 模式會設為 true
    static int activeControlActionCount = 0;     // 本場模擬中，系統主動關閉防火門/啟動排煙的次數

    // ─── ASET(可用安全逃生時間)追蹤 ───────────────────────
    // ASET = 逃生「出口本身」開始變得不可用(著火/濃煙)的那個tick，因為出口一旦失去
    // 可用性，不管走廊多安全，逃生行動實質上都已經失敗。若整場模擬出口自始至終都沒
    // 被威脅到，代表逃生容量始終充足，asetTick維持null，不計入安全餘裕平均。
    static Integer asetTick = null;

    // 察覺模型用：系統(感測器網路)最早偵測到危險/損毀的tick，null代表本場尚未偵測到。
    // SmartEscapeStrategy 的 stillUnaware() 用它模擬「手機收到緊急通知」這個察覺管道。
    static Integer systemAwarenessTick = null;

    // ─── 各情境(DEFAULT/SMART/HYBRID)的統計累加容器 ───────────────
    static Map<SimMode, ModeStats> statsByMode = new LinkedHashMap<>();
    static {
        for (SimMode m : SimMode.values()) statsByMode.put(m, new ModeStats());
    }

	public boolean sessionOutput;

    // ═══════════════════════════════════════════════════════════════════════
    // SimulationContext 實作：People / RouteFinder 對 Simulator 唯一允許的窗口
    // ═══════════════════════════════════════════════════════════════════════
    @Override
    public List<Detector> getDetectors() {
        return detectors;
    }

    @Override
    public boolean isSystemAlertTriggered(int currentTick) {
        return systemAwarenessTick != null && currentTick >= systemAwarenessTick;
    }

    @Override
    public People findPersonById(int id) {
        return allPeopleById.get(id);
    }

    @Override
    public void onStageAssigned() {
        stageAssignCount++;
    }

    // 【校正清單§9】實作countNearbyPeopleOnFloor：線性掃過peopleList統計「同樓層、
    // 以(z,y,x)為中心的正方形鄰域內」還沒逃生/死亡的人數(含自己)，供People.currentSpeed()
    // 算局部人流密度。peopleList通常是數十~數百人量級，每人每tick一次O(N)掃描在目前
    // 的模擬規模下可接受；若之後人數大幅增加，可改成依樓層/座標分桶的空間索引優化。
    @Override
    public int countNearbyPeopleOnFloor(int z, int y, int x, int radiusBlocks) {
        int count = 0;
        for (People p : peopleList) {
            if (p.isDead || p.isEscaped) continue;
            if (p.z != z) continue;
            if (Math.abs(p.y - y) <= radiusBlocks && Math.abs(p.x - x) <= radiusBlocks) {
                count++;
            }
        }
        return count;
    }

    // 【校正清單§5】smokeToleranceFactor()已移除：起火原因對CO毒性的影響改成
    // 直接掛在EnvironmentSimulator生成coPpm的速率上(FireCause.representativeFuel)，
    // 不再需要透過SimulationContext暴露給People。

    // 將 run 修改為回傳每個人的生存時間陣列，並且準確記錄他們的真實結局與暴露統計
    SimResult run(SimMode mode, int totalPeople) {
        activeControlEnabled = (mode == SimMode.HYBRID); // 只有HYBRID額外啟用備援控制
        activeControlActionCount = 0;
        asetTick = null; // 每場模擬重新歸零，ASET是「本場」所有可停留格子第一次全部陷入危險的tick
        systemAwarenessTick = null; // 每場模擬重新歸零，系統示警時間點只在本場有效

        int survive = 0;
        SimResult result = new SimResult(totalPeople);
        stageAssignCount = 0; // 每場模擬重新歸零，避免跟前一場(甚至前一次Default/Smart)累加混在一起
        ConsoleMapPrinter.print(space, tick, peopleList);

        // 原本用 while(!allFloorsOnFire()) 檢查放在迴圈最前面，但起火點在呼叫run()之前
        // 就已經被initFireSource()點燃，對「只有1層樓」的建築來說，第一次檢查時allFloorsOnFire()
        // 就已經是true，導致迴圈本體一次都不會執行。改成do-while，保證迴圈本體至少執行一次tick，
        // 讓煙霧/火勢真的擴散、人員真的有機會行動。
        do {
            tick++;
            activeControlActionCount += EnvironmentSimulator.spread(space, currentCause, tick, activeControlEnabled, random, peopleList);

            // 【校正清單§1/§12】每tick開頭重置門/出口的通過流量瓶頸容量，
            // 見DoorFlowModel。要放在People行動之前，才能讓這個tick的移動判斷
            // (RouteFinder.isPassable)吃到本tick最新的容量。
            DoorFlowModel.resetTick(space);

            // ASET：每tick檢查一次，只要建築裡還有任何一格Floor/Stage沒陷入危險，asetTick維持null；
            // 第一次全部格子都陷入危險時，記下這個tick作為本場的ASET上限。
            if (asetTick == null && EnvironmentSimulator.allOccupiableCellsUntenable(space)) {
                asetTick = tick;
            }

            for (Detector d : detectors) {
                d.update(space.building, random);
            }

            // 察覺模型：系統(感測器網路)第一次偵測到危險或損毀的tick，
            // 之後 SmartEscapeStrategy 的 stillUnaware() 會用它模擬「手機收到緊急通知」這個察覺管道。
            if (systemAwarenessTick == null) {
                for (Detector d : detectors) {
                    if (d.danger || d.broken) { systemAwarenessTick = tick; break; }
                }
            }

            List<People> removed = new ArrayList<>();
            for (People p : peopleList) {
                // 【策略模式】不再需要 if (useSmart) ... else ...，
                // 這個人這場模擬要用哪套邏輯，在建構當下就已經決定好了。
                p.tick(tick);

                if (p.isDead) {
                    result.recordPersonSnapshot(p, tick, Outcome.DEAD);
                    sessionExporter.logEvent(tick, "DEATH", p.id,
                        "人員死亡 (FED_CO: " + String.format("%.3f", p.fedCO) + ")");
                    removed.add(p);
                }
                else if (p.isEscaped) {
                    result.recordPersonSnapshot(p, tick, Outcome.ESCAPED); // 記錄「真正」逃脫的那一刻，而不是模擬結束時的tick
                    sessionExporter.logEvent(tick, "ESCAPE", p.id, "人員成功逃生");
                    survive++;
                    removed.add(p);
                }
            }

            // 記錄本tick快照(在removeAll之前呼叫，讓這個tick剛死亡/逃脫的人員
            // 也能以最終狀態被記錄進這一格timeline，而不是直接消失不留下最後一筆資料)
            if(sessionOutput)
            sessionExporter.recordTick(tick, space, peopleList, detectors);

            peopleList.removeAll(removed);
            ConsoleMapPrinter.print(space, tick, peopleList);
        } while (!(EnvironmentSimulator.allOccupiableCellsUntenable(space) 
        	      && peopleList.isEmpty())
        	      && !EnvironmentSimulator.allFloorsOnFire(space));

        // 迴圈跑完後，還留在 peopleList 裡的人代表模擬結束時他們既沒逃出去也沒死(TRAPPED)
        for (People p : peopleList) {
            result.recordPersonSnapshot(p, tick, Outcome.TRAPPED);
            sessionExporter.logEvent(tick, "TRAPPED", p.id, "模擬結束時仍受困，未逃出也未死亡");
        }

        // 統計本次模擬「進入高煙區」與「CO暴露達臨界值」的人數比例，以及各項KPI
        int highSmokeCount = 0, criticalCOCount = 0, wrongRouteCount = 0;
        int rerouteAttemptCount = 0, rerouteSuccessCount = 0;
        int vulnerableTotal = 0, vulnerableIdentifiedCount = 0;
        int deathCount = 0, trappedCount = 0; // 三分類：逃生/死亡/受困
        int fireDeathCount = 0, coDeathCount = 0; // 死因細分：燒死/CO中毒致死(僅在死亡者中有意義)
        long escapeTickSum = 0, escapeTickN = 0;
        long revokeToRerouteSum = 0, revokeToRerouteN = 0;
        long awarenessSum = 0, awarenessN = 0;
        long cueRsetSum = 0, cueRsetN = 0;

        for (int i = 0; i < totalPeople; i++) {
            if (result.enteredHighSmoke[i]) highSmokeCount++;
            if (result.reachedCriticalCO[i]) criticalCOCount++;
            if (result.wrongRoute[i]) wrongRouteCount++;
            if (result.rerouted[i]) {
                rerouteAttemptCount++;
                if (result.rerouteSuccess[i]) rerouteSuccessCount++;
            }
            if (result.isVulnerableProfile[i]) {
                vulnerableTotal++;
                if (result.vulnerableIdentified[i]) vulnerableIdentifiedCount++;
            }
            if (result.outcomes[i] == Outcome.ESCAPED) {
                escapeTickSum += result.times[i];
                escapeTickN++;
            } else if (result.outcomes[i] == Outcome.DEAD) {
                deathCount++;
                if (result.deathCauses[i] == DeathCause.FIRE) fireDeathCount++;
                else if (result.deathCauses[i] == DeathCause.CO_POISONING) coDeathCount++;
            } else {
                trappedCount++;
            }
            if (result.revokeToRerouteTicks[i] >= 0) {
                revokeToRerouteSum += result.revokeToRerouteTicks[i];
                revokeToRerouteN++;
            }
            if (result.firstCueTick[i] >= 0) {
                awarenessSum += result.firstCueTick[i];
                awarenessN++;
                if (result.outcomes[i] == Outcome.ESCAPED) {
                    cueRsetSum += (result.times[i] - result.firstCueTick[i]);
                    cueRsetN++;
                }
            }
        }

        double wrongRouteRate     = (double) wrongRouteCount / totalPeople;
        double deathRate          = (double) deathCount / totalPeople;
        double trappedRate        = (double) trappedCount / totalPeople;
        double rerouteSuccessRate = (rerouteAttemptCount > 0) ? (double) rerouteSuccessCount / rerouteAttemptCount : -1; // -1代表本場沒有發生改道
        // 死因比例：以「本場死亡人數」為分母，-1代表本場沒有人死亡，不計入平均
        double fireDeathRate = (deathCount > 0) ? (double) fireDeathCount / deathCount : -1;
        double coDeathRate   = (deathCount > 0) ? (double) coDeathCount   / deathCount : -1;
        double vulnerableIdRate   = (vulnerableTotal > 0) ? (double) vulnerableIdentifiedCount / vulnerableTotal : -1;    // -1代表本場沒有需優先協助對象
        double avgEscapeTick      = (escapeTickN > 0) ? (double) escapeTickSum / escapeTickN : -1;
        double avgRevokeToReroute = (revokeToRerouteN > 0) ? (double) revokeToRerouteSum / revokeToRerouteN : -1;
        double avgAwareness       = (awarenessN > 0) ? (double) awarenessSum / awarenessN : -1;
        double avgCueRset         = (cueRsetN > 0) ? (double) cueRsetSum / cueRsetN : -1;

        // ASET−RSET安全餘裕：兩者統一以點火(tick=0)為基準，只有本場「疏散路徑真的全滅過」
        // 且「有人成功逃脫」時才算安全餘裕，避免湊出沒有意義的數字
        Double safetyMargin = (asetTick != null && avgEscapeTick >= 0) ? (asetTick - avgEscapeTick) : null;

        System.out.println("\n====== 模擬結束內部數據 ======");
        ModeStats stats = statsByMode.get(mode);
        stats.totalSurviveP += (double) survive / totalPeople;
        stats.totalDeathP   += deathRate;
        stats.totalTrappedP += trappedRate;
        stats.highSmokeP    += (double) highSmokeCount / totalPeople;
        stats.criticalCOP   += (double) criticalCOCount / totalPeople;
        stats.wrongRouteP   += wrongRouteRate;
        if (rerouteSuccessRate >= 0) { stats.rerouteSuccessSum += rerouteSuccessRate; stats.rerouteIterCount++; }
        if (deathCount > 0) {
            stats.fireDeathCauseSum += fireDeathRate;
            stats.coDeathCauseSum   += coDeathRate;
            stats.deathCauseIterCount++;
        }
        if (vulnerableIdRate  >= 0)  { stats.vulnerableIdSum   += vulnerableIdRate;   stats.vulnerableIterCount++; }
        if (avgEscapeTick     >= 0)  { stats.avgEscapeTickSum  += avgEscapeTick;      stats.escapeTickIterCount++; }
        if (avgRevokeToReroute >= 0) { stats.revokeToRerouteSum += avgRevokeToReroute; stats.revokeToRerouteIterCount++; }
        if (asetTick != null)        { stats.asetSum += asetTick; stats.asetIterCount++; }
        if (safetyMargin != null)    { stats.safetyMarginSum += safetyMargin; stats.safetyMarginIterCount++; }
        if (avgAwareness      >= 0)  { stats.avgAwarenessSum += avgAwareness;   stats.awarenessIterCount++; }
        if (avgCueRset        >= 0)  { stats.avgCueRsetSum += avgCueRset;       stats.cueRsetIterCount++; }
        stats.activeControlActionSum += activeControlActionCount;

        System.err.println("Mode: " + mode + ", total: " + totalPeople + ", survive: " + survive);

        // 匯出本場模擬的 Session JSON(靜態地圖/逐tick快照/事件日誌)
        if(sessionOutput)
        sessionExporter.exportToFile(sessionJsonFilename);

        return result;
    }

    SimResult runOneSimulation(Obj[][][] baseBuilding, int[][] peopleInit,
                                int fireZ, int fireY, int fireX,
                                SimMode mode, String outputFile) {
        try {
            PrintStream output = new PrintStream(outputFile);
            System.setOut(output);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        space = new Space(BuildingGenerator.cloneBuilding(baseBuilding));
        detectors = new ArrayList<>();
        peopleList = new ArrayList<>();
        allPeopleById = new HashMap<>();
        tick = 0;

        // 建立本場模擬的 SessionExporter，並記錄靜態地圖(牆/門/防火門/出口/樓梯間)，
        // JSON檔名依outputFile(defaultResult.txt/smartResult.txt/hybridResult.txt)對應產生。
        String scenarioName = "Building_" + space.height + "x" + space.rows + "x" + space.cols
            + "_Fire(" + fireZ + "," + fireY + "," + fireX + ")_" + mode;
        sessionExporter = new SessionExporter(scenarioName, space.height, space.rows, space.cols);
        sessionExporter.recordStaticMap(space);
        sessionJsonFilename = outputFile.endsWith(".txt")
            ? outputFile.substring(0, outputFile.length() - 4) + "_session.json"
            : outputFile + "_session.json";

        ScenarioInitializer.initFireSource(space, fireZ, fireY, fireX);
        ScenarioInitializer.initDetectors(space, detectors);

        // 【策略模式】依這場模擬的 mode 決定所有人共用哪套 EscapeStrategy，
        // 之後 run() 對每個人統一呼叫 p.tick(tick) 即可，不必再判斷 mode。
        EscapeStrategy strategy = (mode == SimMode.DEFAULT)
            ? new DefaultEscapeStrategy()
            : new SmartEscapeStrategy(); // SMART與HYBRID共用同一套決策支援邏輯

        PersonProfile[] profiles = PersonProfile.values();
        for (int i = 0; i < peopleInit.length; i++) {
            int z = peopleInit[i][0], y = peopleInit[i][1], x = peopleInit[i][2];
            PersonProfile randomProfile = profiles[random.nextInt(profiles.length)];
            peopleList.add(new People(i + 1, z, y, x, space, randomProfile, this, strategy));
        }
        ScenarioInitializer.assignCompanions(peopleList, random, allPeopleById);

        return run(mode, peopleInit.length);
    }

    // work() 跑「多個建築類型 × 每個建築多個火源/人物場景」：
    //   - data：每個元素代表一種建築類型的樓高/樓寬/樓長範圍(Data.H / Data.R / Data.C)，
    //           data.length 即為要抽樣幾種不同的建築幾何(樓層/長/寬)
    //   - count：同一棟建築要再抽幾組不同的起火點+人物配置來跑
    // 每跑完一棟建築的所有場景，先印出「這個建築類型」的小結報表，最後再印出跨所有建築類型的總表。
    public void work(Data[] data, int count) {
        TotalPeopleSum = 0;

        double smartWrongDecisionCount = 0;  // 【全域】SMART比DEFAULT更差的人數（決策支援情境）
        double hybridWrongDecisionCount = 0; // 【全域】HYBRID比DEFAULT更差的人數（決策支援+備援控制情境）

        Map<SimMode, ModeStats> globalStats = new LinkedHashMap<>();
        for (SimMode m : SimMode.values()) globalStats.put(m, new ModeStats());
        int globalScenarioCount = 0;

        for (int bt = 0; bt < data.length; bt++) {
            long buildingStartNano = System.nanoTime();
            Random buildingRand = new Random();
            Range H = data[bt].H, R = data[bt].R, C = data[bt].C;
            int height = buildingRand.nextInt(H.max - H.min) + H.min;
            int rows   = buildingRand.nextInt(R.max - R.min) + R.min;
            int cols   = buildingRand.nextInt(C.max - C.min) + C.min;

            Obj[][][] baseBuilding = BuildingGenerator.generateMap(height, rows, cols);

            // 每個建築類型自己的統計容器：先歸零，這棟樓的所有場景都算完再印小結、再併入全域總表
            for (SimMode m : SimMode.values()) statsByMode.put(m, new ModeStats());

            System.err.println("\n################################################");
            System.err.printf("建築類型 #%d ：%d 層 × %d × %d\n", bt + 1, height, rows, cols);
            System.err.println("################################################");

            BuildingRunSummary summary = runScenariosForBuilding(baseBuilding, data[bt].cause, height, rows, cols, count);

            // 印出「這個建築類型」的小結報表(只彙整這一棟樓、多個火源/人員場景的結果)
            ReportGenerator.printReport("建築類型 #" + (bt + 1) + " (" + height + "層 × " + rows + "×" + cols + ") 小結",
                statsByMode, count, summary.smartWrong, summary.hybridWrong, summary.peopleSum);

            // 時間複雜度量測：這個建築類型從開始到結束總共花了多少毫秒，
            // 除以(count場景數 × 建築體積(height*rows*cols))，得到「每單位(場景數×體積)平均花費的毫秒數」
            long buildingEndNano = System.nanoTime();
            double buildingElapsedMs = (buildingEndNano - buildingStartNano) / 1_000_000.0;
            long buildingVolume = (long) height * rows * cols;
            double msPerUnit = buildingElapsedMs / (count * buildingVolume);
            System.err.printf("執行時間: %.3f ms，體積: %d，時間複雜度量測(ms ÷ (count×體積)) : %.10f ms/unit\n",
                buildingElapsedMs, buildingVolume, msPerUnit);

            smartWrongDecisionCount  += summary.smartWrong;
            hybridWrongDecisionCount += summary.hybridWrong;
            globalScenarioCount      += summary.scenarioCount;

            // 把這棟樓的統計併入跨建築類型的總表
            for (SimMode m : SimMode.values()) globalStats.get(m).mergeFrom(statsByMode.get(m));
        }

        // 印出跨所有建築類型的總表
        ReportGenerator.printReport("跨建築類型總表", globalStats, globalScenarioCount, smartWrongDecisionCount, hybridWrongDecisionCount, TotalPeopleSum);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 自訂房型入口：跟 work(Data[], int) 唯一的差別，只在「這棟建築怎麼來」——
    // 不呼叫 BuildingGenerator.generateMap() 隨機生成，改用 CustomRoomParser
    // 解析使用者手動輸入的三維字串陣列(可多樓層，字元對照表見
    // CustomRoomParser.java檔頭註解)。取得baseBuilding之後，起火點/人物抽樣、
    // DEFAULT/SMART/HYBRID三種模式各跑一次run()、統計彙整、報表列印，全部呼叫
    // 跟work(Data[], int)完全相同的 runScenariosForBuilding()，行為不變。
    //
    //   layout ─ 使用者輸入的房型佈局，layout[z][y][x]即該格符號(見CustomRoomParser)，
    //             z=樓層(z越大代表越高樓層)、y=列、x=行
    //   cause  ─ 這個自訂房型要模擬的起火原因(對應原本Data.cause)
    //   count  ─ 這個房型要重抽幾組不同的起火點+人物配置來跑(對應原本work()的count)
    // ═══════════════════════════════════════════════════════════════════════
    public void workCustomRoom(String[][][] layout, FireCause cause, int count) {
        TotalPeopleSum = 0;

        long buildingStartNano = System.nanoTime();

        Obj[][][] baseBuilding = CustomRoomParser.parse(layout);
        int height = baseBuilding.length, rows = baseBuilding[0].length, cols = baseBuilding[0][0].length;

        // 對齊 BuildingGenerator.generateMap() 尾段(§6可燃物分布、§7統計列印)，
        // 讓自訂房型跟隨機生成房型的後續行為完全一致，只有「怎麼決定牆/門/出口
        // /樓梯的位置」這一步被使用者輸入取代。
        BuildingGenerator.distributeFuelLoads(baseBuilding, height, rows, cols, new Random());
        BuildingGenerator.printCompartmentStats(baseBuilding, height, rows, cols);

        for (SimMode m : SimMode.values()) statsByMode.put(m, new ModeStats());

        System.err.println("\n################################################");
        System.err.printf("自訂房型：%d 層 × %d × %d\n", height, rows, cols);
        System.err.println("################################################");

        BuildingRunSummary summary = runScenariosForBuilding(baseBuilding, cause, height, rows, cols, count);

        ReportGenerator.printReport("自訂房型模擬報表", statsByMode, count, summary.smartWrong, summary.hybridWrong, summary.peopleSum);

        long buildingEndNano = System.nanoTime();
        double buildingElapsedMs = (buildingEndNano - buildingStartNano) / 1_000_000.0;
        long buildingVolume = (long) height * rows * cols;
        double msPerUnit = buildingElapsedMs / (count * buildingVolume);
        System.err.printf("執行時間: %.3f ms，體積: %d，時間複雜度量測(ms ÷ (count×體積)) : %.10f ms/unit\n",
            buildingElapsedMs, buildingVolume, msPerUnit);
        // 註：TotalPeopleSum在runScenariosForBuilding()內已經逐場景累加完成，這裡不需要再處理。
    }

    // 單層房型的便利多載：包成 height=1 的三維陣列後直接呼叫上面那個。
    public void workCustomRoom(String[][] singleFloorLayout, FireCause cause, int count) {
        workCustomRoom(new String[][][]{ singleFloorLayout }, cause, count);
    }

    // ─── work(Data[],int) 與 workCustomRoom(...) 共用：同一棟建築(baseBuilding
    // 幾何不變)重抽count組不同的起火點+人物配置各跑一次，回傳這棟建築彙整後的
    // 統計數字，供呼叫端印小結報表、併入全域總表。內容完全對應原本work()裡
    // for(int sc...)這段迴圈，行為未變更，只是抽成方法讓兩個入口重用。
    private BuildingRunSummary runScenariosForBuilding(Obj[][][] baseBuilding, FireCause cause,
                                                         int height, int rows, int cols, int count) {
        Space tempSpace = new Space(baseBuilding);
        int maxArea = (rows - 2) * (cols - 2);
        if (maxArea < 1) maxArea = 1;

        double buildingSmartWrong = 0, buildingHybridWrong = 0;
        long buildingPeopleSum = 0;
        int scenarioCount = 0;

        for (int sc = 0; sc < count; sc++) {
            System.err.println("-- 場景 " + (sc + 1) + "/" + count + " --");
            Random rand = new Random();
            currentCause = cause;
            System.err.println("起火原因: " + currentCause);

            // 同一棟建築(baseBuilding幾何不變)，每個場景各自重抽一個新的火源位置
            int fireZ = rand.nextInt(tempSpace.height);
            int fireY = rand.nextInt(tempSpace.rows);
            int fireX = rand.nextInt(tempSpace.cols);
            while (!(baseBuilding[fireZ][fireY][fireX] instanceof Floor)) {
                fireY = rand.nextInt(tempSpace.rows);
                fireX = rand.nextInt(tempSpace.cols);
            }

            // 以及各自重抽一組新的人物資訊(人數與位置)
            int peopleCount = Math.max(((rand.nextInt(maxArea) + 1) / 4), 1);
            TotalPeopleSum += peopleCount;
            buildingPeopleSum += peopleCount;

            int[][] peopleInit = new int[peopleCount][4];
            for (int i = 0; i < peopleCount; i++) {
                while (true) {
                    int z = rand.nextInt(tempSpace.height);
                    int y = rand.nextInt(tempSpace.rows);
                    int x = rand.nextInt(tempSpace.cols);
                    if (baseBuilding[z][y][x] instanceof Floor) {
                        peopleInit[i] = new int[]{ z, y, x, rand.nextInt(3) + 1 };
                        break;
                    }
                }
            }

            // 取得每個個體在三種情境下的模擬結果（時間 + 真實結局）：
            //   DEFAULT - 無智慧系統(基準線)
            //   SMART   - 只有決策支援(B1)
            //   HYBRID  - 決策支援 + 備援控制(B2)
            SimResult defaultResult = runOneSimulation(baseBuilding, peopleInit, fireZ, fireY, fireX, SimMode.DEFAULT, "defaultResult.txt");
            SimResult smartResult   = runOneSimulation(baseBuilding, peopleInit, fireZ, fireY, fireX, SimMode.SMART,   "smartResult.txt");
            SimResult hybridResult  = runOneSimulation(baseBuilding, peopleInit, fireZ, fireY, fireX, SimMode.HYBRID,  "hybridResult.txt");

            // 統計「系統做錯決定」的人數：定義為「結果比原本(DEFAULT)更差」
            for (int i = 0; i < peopleCount; i++) {
                boolean defaultSurvived = defaultResult.outcomes[i] == Outcome.ESCAPED;
                boolean smartSurvived   = smartResult.outcomes[i] == Outcome.ESCAPED;
                boolean hybridSurvived  = hybridResult.outcomes[i] == Outcome.ESCAPED;

                if (defaultSurvived && !smartSurvived)  { buildingSmartWrong++; }
                if (defaultSurvived && !hybridSurvived) { buildingHybridWrong++; }
            }

            scenarioCount++;
        }

        return new BuildingRunSummary(buildingSmartWrong, buildingHybridWrong, buildingPeopleSum, scenarioCount);
    }

    // 單一建築(不論隨機生成或自訂房型)跑完count個場景後的彙整結果。
    private static final class BuildingRunSummary {
        final double smartWrong;
        final double hybridWrong;
        final long peopleSum;
        final int scenarioCount;

        BuildingRunSummary(double smartWrong, double hybridWrong, long peopleSum, int scenarioCount) {
            this.smartWrong = smartWrong;
            this.hybridWrong = hybridWrong;
            this.peopleSum = peopleSum;
            this.scenarioCount = scenarioCount;
        }
    }
}
