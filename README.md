# 火場決策支援系統效果量化模擬器

本模擬器旨在透過客觀的統計與數學模型，量化「決策支援（SMART）」**與**「決策支援＋備援主動控制（HYBRID）」**兩種智慧消防系統，相較於**「無系統（DEFAULT）」情境對火場逃生表現的實際影響。

模擬設計參考消防工程之 **ASET（Available Safety Egress Time，可用安全疏散時間）** 與 **RSET（Required Safety Egress Time，所需安全疏散時間）** 框架，透過嚴謹的常數設定與離散時間步（Tick）演算法，消除主觀的人類反應延遲假設，提供客觀的數據量化基礎。

> **重要聲明**：本文件與模擬器僅描述模擬軟體本身的統計邏輯、物理假設與參數定義，非判定真實建築消防安全之法規依據。

---

## 1. 三種比較情境與主動控制機制

同一棟建築配置、起火點與人員分布，會依序在以下三種模式中跑完完整的生命週期，並比較統計數據：

| 模式 | 技術說明與主動控制機制 |
| --- | --- |
| **DEFAULT** | **無智慧系統模式**：人物無法取得全域資訊。完全依賴個人直覺（即有限的局部視線與嗅覺），透過廣度優先搜尋（BFS）進行盲目逃生。 |
| **SMART** | **決策支援模式**：系統透過全域感測器網路進行路徑規劃並給予逃生指引。人物服從率設定為恆定常數 **85%** (`COMPLIANCE_RATE = 0.85`)。其餘 15% 則退回 DEFAULT 決策。 |
| **HYBRID** | **決策支援＋備援主動控制模式**：除了具備 SMART 模式的指引功能外，系統若偵測到危險（半徑 2 格內 `smoke > 0.5` 或網格著火），將自動觸發兩項主動實體控制：<br>

<br>1. **強制關閉**該區域所有未關緊的防火門（`FIRE_DOOR`）。<br>

<br>2. **啟動局部排煙**，使該受災區域的煙霧每步增長率乘上 **0.85** 的折減係數。 |

---

## 2. 人員結局判定與關鍵指標（KPI）定義

每個人員在模擬結束後，必然歸類為以下三種最終狀態（`Outcome` Enum）之一：

* **ESCAPED（成功逃生）**：人員移動至任意標記為出口（`Exit`）的網格，且此時該人員仍處於存活狀態。
* **DEAD（不幸罹難）**：
1. 踏入已著火的網格（`fire == true`）。
2. 人員累積的一氧化碳暴露量（`accumulatedCO`）達到或超過其個人的**有效致命閾值**。


* **TRAPPED（受困）**：當模擬達到最大 Tick 上限時，人員既未逃脫亦未死亡，仍滯留於建築物內。

### 系統額外監測之 KPI 指標：

* **高煙區暴露比例（High Smoke Exposure Rate）**：人員在移動路徑中，曾進入煙霧濃度大於或等於 **0.7**（`HIGH_SMOKE_THRESHOLD`）網格的比例。
* **臨界 CO 暴露量（Critical CO Exposure）**：人員累積之 CO 暴露量達到其個人致命閾值的 **80%**（`CRITICAL_CO_RATIO = 0.8`）。

---

## 3. 察覺（Awareness）機制與視線演算法

人員必須在狀態轉變為「已察覺（`isAware = true`）」後，才開始進行路徑規劃與移動。在此之前，人員將於原地滯留。

### 3.1 DEFAULT（無系統）情境下的察覺觸發

人員僅能透過自身感官觸發察覺：

* **嗅覺觸發**：所在網格的煙味強度大於或等於 **0.05**（`AWARENESS_SMOKE_THRESHOLD`）。
* **視覺觸發**：利用陰影投射演算法（Shadow Casting Algorithm）計算視線（Line of Sight, LOS）。
* **視線阻擋物定義**：牆壁（`WALL`）、著火網格（`fire == true`）、高濃度煙霧（`smoke > 0.7`）及關閉的門（`DOOR` / `FIRE_DOOR`）均視為完全不透光之障礙。
* **演算法邏輯**：從人員網格中心向外發射射線，計算障礙物頂點所產生的 2D 向量外積與斜率區間，動態標記被陰影遮蔽的網格。若在此視線盲區外的可見網格中存在火源（`fire`）或高濃度煙霧，則立即觸發察覺。



### 3.2 SMART / HYBRID 情境下的察覺觸發

若行動裝置電力與訊號正常，當全域感測器網路首次回報危險數據（`danger = true` 或 `broken = true`）時，系統將主動發送警報，強制將所有連線人員的狀態設為「已察覺」。

---

## 4. 環境危害物非線性擴散模型

網格（`Grid`）之環境危害屬性包含 `fire`（Boolean）、`smoke`（0.0 ~ 1.0）與 `smell`（0.0 ~ 1.0）。

### 4.1 實體障礙（門）之穿透機率

危害物擴散至相鄰網格時，若邊界存在「門」，該 Tick 的穿透與否由獨立的隨機機率檢定判定：

| 危害類型 | 關閉之防火門（`FIRE_DOOR`）穿透率 | 普通門（`DOOR`）穿透率 |
| --- | --- | --- |
| **火源 (Fire)** | $5\%$ | $35\%$ |
| **煙霧 (Smoke)** | $45\%$ | $65\%$ |
| **煙味 (Smell)** | $75\%$ | $90\%$ |

### 4.2 煙霧基準增長公式

煙霧濃度 $S$ 隨時間的自主非線性增長計算公式如下：


$$S_{next} = \left\vert{} \sin\left(S_{current} - \frac{\pi}{2}\right) \right\vert{}^{0.68} + 1.0$$

### 4.3 起火原因與時間相位因子（Temporal Spread Factor）

擴散速率會依據建築的起火原因（`FireCause`）與已模擬的時間 $t$（Ticks）進行非線性修正，並加成於基本擴散倍率：

* **電線走火（ELECTRICAL）**：初期短路延燒極快，隨後隨能量耗損指數衰減。

$$F(t) = 1.0 + 1.2 e^{-\frac{t}{15.0}}$$


* **化學起火（CHEMICAL）**：初期反應緩慢，直至臨界點發生閃燃後呈 Logistic S 曲線急速爆發。

$$F(t) = 0.6 + \frac{1.4}{1.0 + e^{-\frac{t - 25.0}{6.0}}}$$


* **縱火（ARSON）**：因助燃劑作用，初期即達擴散高峰，隨後緩慢遞減。

$$F(t) = 1.5 - 0.5 \left(1.0 - e^{-\frac{t}{30.0}}\right)$$


* **一般意外（ACCIDENTAL）**：標準線性擴散速度。

$$F(t) = 1.0$$



---

## 5. 智慧系統硬體與環境限制參數

為反映真實世界中的硬體缺陷，模擬器導入了以下物理限制：

* **行動裝置電力限制**：
* 初始無電量機率：**5%** (`PHONE_DEAD_INIT_CHANCE = 0.05`)
* 每步（Tick）電力耗盡機率：**0.15%** (`PHONE_DEAD_TICK_CHANCE = 0.0015`)


* **通訊網路限制**：
* 每步訊號瞬斷機率：**0.1%** (`NETWORK_DROP_TICK_CHANCE = 0.001`)


* **定位誤差模型**：
* 系統取得之人員定位資訊並非絕對精確，而是加入基於常態分布的雜訊：

$$\Delta X, \Delta Y \sim N(\mu=0, \sigma^2=1.5^2)$$



即水平與垂直定位標準差為 **1.5 格**（`POS_ERROR_STDDEV = 1.5`）。


* **全域感測器網路誤差與故障**：
* 每步感測器硬體隨機損毀/故障率：**0.2%** (`MALFUNCTION_CHANCE = 0.002`)。
* 感測值讀數讀取誤差：加入常態分布雜訊 $e \sim N(0, 0.15^2)$（`NOISE_STDDEV = 0.15`）。當含噪讀數大於 **0.7** 時，判定該網格處於危險狀態。



---

## 6. 決策支援系統之 Dijkstra 尋路與指派演算法

當人員處於 SMART 或 HYBRID 模式且連線正常時，系統會為其規劃最安全的撤離路徑。指派邏輯採**兩階段任務規劃**（跨樓層 `targetStage` 與同樓層 `junctionTargetPos`）。

### 6.1 路徑權重計算

尋路演算法採用加權 Dijkstra 演算法，網格邊緣的移動成本（Edge Weight）根據危害程度進行動態加成懲罰：


$$\text{Weight} = \text{BaseStepCost} + \text{DangerWeight} + \text{FireExtraWeight}$$

* **基礎步長成本（Base Step Cost）**：`EXIT_STEP_WEIGHT = 0.0`（以距離為基準）。
* **感測危險懲罰（Danger Weight）**：

$$\text{DangerWeight} = \frac{0.5}{1.0 + \text{ManhattanDistToDanger}}$$


* **著火網格懲罰（Fire Extra Weight）**：

$$\text{FireExtraWeight} = \frac{5.0}{1.0 + \text{ManhattanDistToFire}}$$



### 6.2 撤回機制與路徑改道（Rerouting）

每步系統都會利用已知危險（含故障與回報危險之感測網格）對目標出口進行廣度優先搜尋（BFS）連通性檢查（`isReachable`）。

* 若當前指派出口已被火勢完全封鎖，系統將立即撤回當前指派，重新執行 Dijkstra 尋路並指派新出口，同時將人員標記為已改道（`everRerouted = true`）。
* 若經演算法判定所有出口皆無法連通，系統將自動退回「最大化存活時間策略」（`maximizeSurvivalTime`），指派人員往目前火勢與煙霧最稀薄的區域避難。

---

## 7. 人員角色（PersonProfile）細部屬性與特殊行為定義

模擬器中的人員具有高度異質性，各屬性設定如下：

| 角色類型 | 移動速度<br>

<br>(格/Tick) | 基準 CO 致命閾值 | 恐慌易感度 | 特殊行為邏輯與限制 |
| --- | --- | --- | --- | --- |
| **NORMAL_SOLO** | 3 | 25.0 | 0.3 | 獨立個體，無同行伴侶或依賴者。 |
| **CUSTOMER** | 3 | 25.0 | 0.3 | 顧客。對建築結構不熟悉，DEFAULT 下尋路能力受限。 |
| **STAFF** | 3 | 25.0 | 0.3 | 員工。預設熟悉建築結構，擁有所有安全梯（`Stage`）的精確位置。 |
| **WITH_CHILD** | 2 | 20.0 | 0.6 | 攜帶幼童。開局必須先在原地花費 $3 \sim 8$ Tick（`childGatherDelay`）與幼童會合後才可開始逃生。 |
| **IMPAIRED** | 1 | 15.0 | 0.8 | 行動不便者。若當前無立即危險，每 Tick 有 **2%** 機率（`IMPAIRED_WAIT_CHANCE`）放棄移動並於原地等待救援。 |
| **ELDERLY** | 1 | 12.0 | 0.7 | 年長者。生理耐受度極低，會被智慧系統標記為最優先指引與協助對象。 |

### 7.1 同行伴侶與等待機制（Companion Mechanics）

* 所有人員在初始化時，有 **35%** 的隨機機率被配對為同行伴侶（Companion）。
* 在逃生過程中，若其中一人落後另一人超過 **4 格**，領先者每步將有 **50%** 的機率（`COMPANION_WAIT_CHANCE = 0.5`）選擇暫停移動（原地等待）或折返向落後者的座標靠攏，直至兩人距離縮小至安全範圍內。

---

## 8. 軌跡檔（`SessionExporter`）JSON 格式 Schema 定義

模擬結束後會自動匯出 `[SceneName]_session.json`，供前端 3D 渲染器進行視覺化回放。其結構定義如下：

```json
{
  "metadata": {
    "sceneName": "STRING (場景名稱)",
    "height": "INT (樓層數)",
    "rows": "INT (網格行數)",
    "cols": "INT (網格列數)",
    "exportTime": "STRING (ISO 時間戳記)"
  },
  "staticMap": [
    {
      "x": "INT",
      "y": "INT",
      "z": "INT",
      "type": "STRING (WALL | EXIT | STAGE | DOOR | FIRE_DOOR)",
      "extraFlag": "BOOLEAN (防火門是否未關緊等)"
    }
  ],
  "eventLogs": [
    {
      "tick": "INT",
      "personId": "INT",
      "eventType": "STRING (DEATH | ESCAPE | TRAPPED | SYSTEM_FAIL)",
      "description": "STRING"
    }
  ],
  "timeline": [
    {
      "tick": "INT",
      "people": [
        {
          "id": "INT",
          "x": "DOUBLE (含定位雜訊與誤差)",
          "y": "DOUBLE",
          "z": "INT",
          "isDead": "BOOLEAN",
          "accumulatedCO": "DOUBLE",
          "panicLevel": "DOUBLE (0.0 ~ 1.0)",
          "currentTask": "STRING (MOVE_TO_EXIT | GATHER_CHILD | WAIT_RESCUE)"
        }
      ],
      "detectors": [
        {
          "id": "INT",
          "x": "INT",
          "y": "INT",
          "z": "INT",
          "broken": "BOOLEAN",
          "danger": "BOOLEAN"
        }
      ],
      "environment": [
        {
          "x": "INT",
          "y": "INT",
          "z": "INT",
          "fire": "BOOLEAN",
          "smoke": "DOUBLE (僅在 smoke > 0.01 時記錄，以壓縮空間)"
        }
      ]
    }
  ]
}

```