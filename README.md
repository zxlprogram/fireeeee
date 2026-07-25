# 火場決策支援系統效果量化模擬器

## 專案介紹 (Project Overview)

本模擬器旨在透過客觀的統計與數學模型，量化「決策支援（SMART）」與「決策支援＋備援主動控制（HYBRID）」兩種智慧消防系統，相較於「無系統（DEFAULT）」情境對火場逃生表現的實際影響。

模擬設計參考消防工程之 **ASET**（Available Safety Egress Time，可用安全疏散時間）與 **RSET**（Required Safety Egress Time，所需安全疏散時間）框架，並進一步接入 NFPA/SFPE Handbook、ISO 13571、Purser 劑量模型、Weibull 存活分布、Fick's Second Law 擴散方程等消防工程與火災動力學文獻中的公式與量級，取代早期版本中大量「固定機率／人工曲線」式的簡化代理，提供更貼近物理真實的量化基礎。

> **重要聲明**：本文件與模擬器僅描述模擬軟體本身的統計邏輯、物理假設與參數定義，非判定真實建築消防安全之法規依據。文中多處數值屬工程判斷取的合理量級，非逐一文獻覆核的精確值，若有明確標註「無文獻對應」則代表該數值目前搜尋範圍內找不到可直接引用的出處，純屬工程假設。

---

## 三種模擬模式 (Simulation Modes)

同一棟建築配置、起火點與人員初始位置，會依序在以下三種模式中跑完完整的生命週期，並比較統計數據：

| 模式 | 說明 |
| --- | --- |
| **DEFAULT** | 無智慧系統模式，人員完全依賴個人局部視線與嗅覺自行逃生。 |
| **SMART** | 決策支援模式，系統透過全域感測器網路提供路徑指引（服從率 85%）。 |
| **HYBRID** | 決策支援＋備援主動控制模式，除路徑指引外，系統可自動關閉危險防火門並提升排煙換氣率。 |

三種模式在**同一場景**下各自完整跑一次，藉此比較系統介入是否反而導致原本能逃生的人未能逃生，作為「系統嚴重失誤機率」的量化依據。

---

## 專案特色 (Features)

* 以 Purser FED（Fractional Effective Dose）模型計算熱暴露與 CO 吸入之累積劑量死亡判定，取代早期「踩到火格即死」的簡化邏輯。
* 完整 HRR → 燃料 → 通風鏈：依 $t^2$ 火災成長曲線與燃料資料庫動態計算 CO／煙霧生成與擴散。
* 四判据 ASET 判定（溫度／輻射熱通量／CO 毒性／能見度），符合 ISO 13571 OR 邏輯慣例。
* 門的耐火失效改採 Weibull 存活分布，取代固定突破機率。
* 門對擴散通量的洩漏改採孔口流量公式（orifice flow equation），取代固定穿越機率。
* 煙霧擴散採 Fick's Second Law 有限差分模型，具數值穩定性自動調整機制。
* Dijkstra 決策支援路徑規劃，含危險硬性排除、危險記憶機制與路徑改道保護。
* 人流密度抑制移動速度（Fruin/SFPE 基本圖）與門／出口流量瓶頸模型。
* 高度異質性人員角色（NORMAL_SOLO／CUSTOMER／STAFF／WITH_CHILD／IMPAIRED／ELDERLY）與同行伴侶等待機制。
* 可匯出完整軌跡 JSON，供前端 3D 渲染器回放。

---

## 文件導覽 (Documentation Navigation)

| 文件 | 內容 |
| --- | --- |
| [docs/01-overview.md](docs/01-overview.md) | 情境設定、人員結局判定與 KPI 指標定義 |
| [docs/02-evacuation-model.md](docs/02-evacuation-model.md) | 察覺機制、準備時間、累積劑量死亡判定、人流密度與瓶頸模型 |
| [docs/03-fire-model.md](docs/03-fire-model.md) | HRR 成長曲線、燃料資料庫、CO／煙霧生成與擴散、溫度場與 ASET |
| [docs/04-door-and-ventilation.md](docs/04-door-and-ventilation.md) | 門的耐火失效模型、樓梯間防火隔間、孔口流量洩漏模型 |
| [docs/05-routing.md](docs/05-routing.md) | Dijkstra 尋路、危險記憶機制、路徑改道與主動關門保護 |
| [docs/06-building.md](docs/06-building.md) | 人員角色屬性、同行伴侶機制、建築生成與地圖結構 |
| [docs/07-json-schema.md](docs/07-json-schema.md) | 軌跡檔（SessionExporter）JSON 格式 Schema 定義 |
| [docs/references.md](docs/references.md) | 參考文獻與標準（NFPA/SFPE、ISO 13571、Purser、Weibull 等） |

---

## 如何執行 (How to Run)

> 以下為執行流程範例，實際指令請依專案實作環境調整。

```bash
# 安裝相依套件
npm install

# 執行模擬（DEFAULT / SMART / HYBRID 三模式將依序完整跑一次）
npm run simulate

# 模擬結束後於輸出目錄取得 [SceneName]_session.json
# 可用於前端 3D 渲染器回放，格式詳見 docs/07-json-schema.md
```

---

## License

本專案採用 [MIT License](LICENSE) 授權。
