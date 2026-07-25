[← 上一篇：06 · 建築生成與地圖結構](06-building.md) ｜ [回首頁](../README.md) ｜ 下一篇：[參考文獻](references.md) →

# 軌跡檔（`SessionExporter`）JSON 格式 Schema 定義

模擬結束後可匯出 `[SceneName]_session.json`，供前端 3D 渲染器進行視覺化回放。其結構定義如下：

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
      "eventType": "STRING (AWARE | ADVICE_ISSUED | ADVICE_REVOKED | REROUTE | TRAPPED | ESCAPE | DEATH | SYSTEM_FAIL)",
      "description": "STRING"
    }
  ],
  "timeline": [
    {
      "tick": "INT",
      "people": [
        {
          "id": "INT",
          "position": "[z, y, x]（人員實際座標，不含定位雜訊）",
          "profile": "STRING",
          "isDead": "BOOLEAN",
          "isEscaped": "BOOLEAN",
          "aware": "BOOLEAN",
          "fedCO": "DOUBLE（Purser FED_CO 累積劑量，無因次，取代原本的 accumulatedCO）",
          "panicLevel": "DOUBLE (0.0 ~ 1.0)",
          "networkConnected": "BOOLEAN",
          "currentTask": "STRING (ESCAPED | DEAD | WAIT_RESCUE | PREMOVEMENT_DELAY | MOVE_FLOOR | ESCAPE_ROUTE | IDLE)"
        }
      ],
      "detectors": [
        {
          "position": "[z, y, x]",
          "broken": "BOOLEAN",
          "danger": "BOOLEAN"
        }
      ],
      "environment": [
        {
          "position": "[z, y, x]",
          "fire": "BOOLEAN",
          "smoke": "DOUBLE（能見度危害代理量，僅在 smoke > 0.01、coPpm > 1.0、溫度高於常溫 0.5°C 以上、或著火時記錄，以壓縮空間）",
          "coPpm": "DOUBLE（CO 濃度，ppm，新增欄位，與 smoke 分開追蹤）",
          "tempC": "DOUBLE（氣體溫度，°C，新增欄位）"
        }
      ]
    }
  ]
}
```

### 欄位對應說明

* `people[].fedCO` 對應 [02 · CO 吸入劑量（FED_CO）](02-evacuation-model.md#co-吸入劑量fed_co) 之累積劑量模型。
* `environment[].smoke` / `coPpm` / `tempC` 分別對應 [03 · 火災與煙霧擴散模型](03-fire-model.md) 中拆分追蹤的能見度代理量、CO 濃度與溫度場。
* `staticMap[].type` 中的 `FIRE_DOOR` 對應 [04 · 門的耐火失效與通風洩漏模型](04-door-and-ventilation.md) 所描述之防火門與樓梯間防火隔間門。

---

[← 上一篇：06 · 建築生成與地圖結構](06-building.md) ｜ [回首頁](../README.md) ｜ 下一篇：[參考文獻](references.md) →
