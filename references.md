[← 上一篇：07 · JSON Schema](07-json-schema.md) ｜ [回首頁](../README.md)

# 參考文獻與標準

本模擬器之公式與量級設計參考以下消防工程與火災動力學文獻、標準與模型。如前言所述，文中多處數值屬工程判斷取的合理量級，非逐一文獻覆核的精確值。

---

## 逃生時間框架

* **ASET / RSET 框架**——Available Safety Egress Time（可用安全疏散時間）與 Required Safety Egress Time（所需安全疏散時間），為消防工程評估逃生安全性的核心框架，貫穿全模擬器之終止條件與安全餘裕計算（詳見 [01 · 情境設定與 KPI 指標](01-overview.md)、[03 · 模擬終止條件與 ASET−RSET 安全餘裕](03-fire-model.md#3-模擬終止條件與-asetrset-安全餘裕)）。

## 火災動力學與煙控

* **NFPA/SFPE Handbook**——提供 $t^2$ 火災成長曲線分類（SLOW／MEDIUM／FAST／ULTRA_FAST）與全盛期 HRR 量級之常見引用來源，詳見 [03 · 熱釋放率（HRR）成長曲線](03-fire-model.md#11-熱釋放率hrr成長曲線)。
* **Fick's Second Law**（$\partial C/\partial t = D\nabla^2C$）——用於煙霧空間擴散之有限差分模型，詳見 [03 · 煙霧擴散模型](03-fire-model.md#4-煙霧擴散模型ficks-second-law-有限差分擴散)。
* **Stefan-Boltzmann 定律**——用於由溫度場反推輻射熱通量，詳見 [03 · 輻射熱通量](03-fire-model.md#21-輻射熱通量)。

## 毒理與人體耐受模型

* **ISO 13571**——定義 CO 失能門檻（FED_INCAPACITATION = 0.3）與四判据 untenable 判定之 OR 邏輯慣例，詳見 [01 · KPI 指標定義](01-overview.md)、[03 · 四判据 untenable 判定（ASET）](03-fire-model.md#23-四判据-untenable-判定aset)。
* **Purser 劑量模型（Fractional Effective Dose, FED）**——分別用於 CO 吸入劑量（%COHb 增量公式）與熱暴露耐受時間公式，詳見 [02 · 累積劑量死亡判定](02-evacuation-model.md#2-累積劑量死亡判定)。

## 門與防火區劃

* **Weibull 存活分布**——用於門的耐火失效模型，取代固定突破機率，詳見 [04 · 門的耐火失效模型](04-door-and-ventilation.md#1-門的耐火失效模型weibull-存活分布取代固定機率)。
* **UL 1784**——防火門洩漏率測試標準，提供孔口流量模型之測試壓差（24.9 Pa）與認證洩漏率上限依據，詳見 [04 · 門對擴散通量的孔口流量洩漏模型](04-door-and-ventilation.md#2-門對擴散通量的孔口流量洩漏模型取代固定穿越機率)。
* **NFPA 105 / IBC**——與 UL 1784 並列，作為防火門認證洩漏率上限之參考依據。

## 人流與步行速度模型

* **SFPE Handbook（Fruin 1971 / Predtechenskii-Milinskii）**——走廊與樓梯步行速度隨人流密度抑制之線性近似公式 $S = k_1 - k_2 D$，詳見 [02 · 人流密度對移動速度的抑制](02-evacuation-model.md#4-人流密度對移動速度的抑制fruinsfpe-基本圖)。

---

[← 上一篇：07 · JSON Schema](07-json-schema.md) ｜ [回首頁](../README.md)
