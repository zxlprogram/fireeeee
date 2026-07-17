package simulator;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class SessionExporter {

    // ─── 內部資料結構：事件記錄 ─────────────────────────────────
    public static class EventLog {
        public int tick;
        public String eventType; // "AWARE", "ADVICE_ISSUED", "ADVICE_REVOKED", "REROUTE", "TRAPPED", "ESCAPE", "DEATH"
        public int personId;
        public String description;

        public EventLog(int tick, String eventType, int personId, String description) {
            this.tick = tick;
            this.eventType = eventType;
            this.personId = personId;
            this.description = description;
        }

        public String toJson(String indent) {
            return indent + "{\n" +
                   indent + "  \"tick\": " + tick + ",\n" +
                   indent + "  \"eventType\": \"" + eventType + "\",\n" +
                   indent + "  \"personId\": " + personId + ",\n" +
                   indent + "  \"description\": \"" + escapeJson(description) + "\"\n" +
                   indent + "}";
        }
    }

    // ─── 內部資料結構：每 Tick 的快照 ───────────────────────────
    public static class TickSnapshot {
        public int tick;
        public List<PersonState> peopleStates = new ArrayList<>();
        public List<DetectorState> detectorStates = new ArrayList<>();
        public List<GridState> dynamicGrids = new ArrayList<>(); // 僅記錄有火或有煙的格點，節省空間

        public TickSnapshot(int tick) {
            this.tick = tick;
        }

        public String toJson(String indent) {
            StringBuilder sb = new StringBuilder();
            sb.append(indent).append("{\n");
            sb.append(indent).append("  \"tick\": ").append(tick).append(",\n");

            // People States
            sb.append(indent).append("  \"people\": [\n");
            for (int i = 0; i < peopleStates.size(); i++) {
                sb.append(peopleStates.get(i).toJson(indent + "    "));
                if (i < peopleStates.size() - 1) sb.append(",\n");
            }
            sb.append("\n").append(indent).append("  ],\n");

            // Detector States
            sb.append(indent).append("  \"detectors\": [\n");
            for (int i = 0; i < detectorStates.size(); i++) {
                sb.append(detectorStates.get(i).toJson(indent + "    "));
                if (i < detectorStates.size() - 1) sb.append(",\n");
            }
            sb.append("\n").append(indent).append("  ],\n");

            // Dynamic Grids (Fire/Smoke)
            sb.append(indent).append("  \"environment\": [\n");
            for (int i = 0; i < dynamicGrids.size(); i++) {
                sb.append(dynamicGrids.get(i).toJson(indent + "    "));
                if (i < dynamicGrids.size() - 1) sb.append(",\n");
            }
            sb.append("\n").append(indent).append("  ]\n");

            sb.append(indent).append("}");
            return sb.toString();
        }
    }

    public static class PersonState {
        public int id;
        public int z, y, x;
        public String profile;
        public boolean isDead;
        public boolean isEscaped;
        public boolean aware;
        public double accumulatedCO;
        public double panicLevel;
        public boolean networkConnected;
        public String currentTask; // "ESCAPE", "WAIT_RESCUE", "WAIT_COMPANION", "GATHERING_CHILD", "IDLE"

        public String toJson(String indent) {
            return indent + "{\n" +
                   indent + "  \"id\": " + id + ",\n" +
                   indent + "  \"position\": [" + z + ", " + y + ", " + x + "],\n" +
                   indent + "  \"profile\": \"" + profile + "\",\n" +
                   indent + "  \"isDead\": " + isDead + ",\n" +
                   indent + "  \"isEscaped\": " + isEscaped + ",\n" +
                   indent + "  \"aware\": " + aware + ",\n" +
                   indent + "  \"accumulatedCO\": " + String.format("%.3f", accumulatedCO) + ",\n" +
                   indent + "  \"panicLevel\": " + String.format("%.3f", panicLevel) + ",\n" +
                   indent + "  \"networkConnected\": " + networkConnected + ",\n" +
                   indent + "  \"currentTask\": \"" + currentTask + "\"\n" +
                   indent + "}";
        }
    }

    public static class DetectorState {
        public int z, y, x;
        public boolean broken;
        public boolean danger;

        public String toJson(String indent) {
            return indent + "{\n" +
                   indent + "  \"position\": [" + z + ", " + y + ", " + x + "],\n" +
                   indent + "  \"broken\": " + broken + ",\n" +
                   indent + "  \"danger\": " + danger + "\n" +
                   indent + "}";
        }
    }

    public static class GridState {
        public int z, y, x;
        public boolean fire;
        public double smoke;

        public String toJson(String indent) {
            return indent + "{\n" +
                   indent + "  \"position\": [" + z + ", " + y + ", " + x + "],\n" +
                   indent + "  \"fire\": " + fire + ",\n" +
                   indent + "  \"smoke\": " + String.format("%.3f", smoke) + "\n" +
                   indent + "}";
        }
    }

    // ─── Exporter 主體欄位 ─────────────────────────────────────
    private String scenarioName;
    private int height, rows, cols;
    private List<EventLog> events = new ArrayList<>();
    private List<TickSnapshot> ticks = new ArrayList<>();
    private List<String> staticElementsJson = new ArrayList<>();

    public SessionExporter(String scenarioName, int height, int rows, int cols) {
        this.scenarioName = scenarioName;
        this.height = height;
        this.rows = rows;
        this.cols = cols;
    }

    // ─── 記錄靜態地圖元素（初始化時呼叫一次） ───────────────────────
    public void addStaticElement(int z, int y, int x, String type, boolean extraFlag) {
        String json = "      {\n" +
                      "        \"position\": [" + z + ", " + y + ", " + x + "],\n" +
                      "        \"type\": \"" + type + "\",\n" +
                      "        \"extraFlag\": " + extraFlag + "\n" +
                      "      }";
        staticElementsJson.add(json);
    }

    // ─── 【新增，原本掛在Simulator上】掃描整張Space，把牆/門/防火門/出口/樓梯間
    // 這些靜態結構寫入本次匯出。這本來就是SessionExporter自身資料格式的延伸(呼叫
    // addStaticElement())，放在Simulator裡反而是不必要的耦合，現在收斂回這個類別自己負責。
    // Floor 不記錄(數量最多，且對前端呈現意義不大)，藉此節省JSON檔案大小。
    public void recordStaticMap(Space sp) {
        for (int z = 0; z < sp.height; z++) {
            for (int y = 0; y < sp.rows; y++) {
                for (int x = 0; x < sp.cols; x++) {
                    Obj o = sp.building[z][y][x];
                    String type;
                    boolean extraFlag = false;
                    if (o instanceof FireDoor) {
                        type = "FIRE_DOOR";
                        extraFlag = ((FireDoor) o).isOpen; // true代表沒關好，防護效果退化成普通門
                    } else if (o instanceof Door) {
                        type = "DOOR";
                        extraFlag = ((Door) o).blocked;
                    } else if (o instanceof Exit) {
                        type = "EXIT";
                    } else if (o instanceof Wall) {
                        type = "WALL";
                    } else if (o instanceof Stage) {
                        type = "STAGE";
                    } else {
                        continue; // Floor 或其他不記錄
                    }
                    addStaticElement(z, y, x, type, extraFlag);
                }
            }
        }
    }

    // ─── 記錄關鍵事件日誌（模擬中發生時即時呼叫） ───────────────────
    public void logEvent(int tick, String eventType, int personId, String description) {
        events.add(new EventLog(tick, eventType, personId, description));
    }

    // ─── 記錄每 Tick 狀態快照（每個 tick 結束時呼叫） ─────────────────
    public void recordTick(int currentTick, Space space, List<People> peopleList, List<Detector> detectorList) {
        TickSnapshot snapshot = new TickSnapshot(currentTick);

        // 1. 紀錄人員狀態
        for (People p : peopleList) {
            PersonState ps = new PersonState();
            ps.id = p.id;
            ps.z = p.z;
            ps.y = p.y;
            ps.x = p.x;
            ps.profile = p.profile.name();
            ps.isDead = p.isDead;
            ps.isEscaped = p.isEscaped;
            ps.aware = p.aware;
            ps.accumulatedCO = p.accumulatedCO;
            ps.panicLevel = p.panic.panicLevel;
            ps.networkConnected = p.device.networkConnected;

            // 判斷當前主要行動任務
            if (p.isEscaped) {
                ps.currentTask = "ESCAPED";
            } else if (p.isDead) {
                ps.currentTask = "DEAD";
            } else if (p.profile == PersonProfile.IMPAIRED && p.waitingForRescue) {
                ps.currentTask = "WAIT_RESCUE";
            } else if (p.childGatherDelay > 0) {
                ps.currentTask = "GATHERING_CHILD";
            } else if (p.targetStage != null) {
                ps.currentTask = "MOVE_FLOOR";
            } else if (p.junctionTargetPos != null) {
                ps.currentTask = "ESCAPE_ROUTE";
            } else {
                ps.currentTask = "IDLE";
            }
            snapshot.peopleStates.add(ps);
        }

        // 2. 紀錄感測器網路
        for (Detector d : detectorList) {
            DetectorState ds = new DetectorState();
            ds.z = d.z;
            ds.y = d.y;
            ds.x = d.x;
            ds.broken = d.broken;
            ds.danger = d.danger;
            snapshot.detectorStates.add(ds);
        }

        // 3. 紀錄動態火煙環境（只記錄有煙霧 smoke > 0.01 或有起火的網格，優化檔案大小）
        Obj[][][] map = space.building;
        for (int z = 0; z < space.height; z++) {
            for (int y = 0; y < space.rows; y++) {
                for (int x = 0; x < space.cols; x++) {
                    Obj obj = map[z][y][x];
                    if (obj.fire || obj.smoke > 0.01) {
                        GridState gs = new GridState();
                        gs.z = z;
                        gs.y = y;
                        gs.x = x;
                        gs.fire = obj.fire;
                        gs.smoke = obj.smoke;
                        snapshot.dynamicGrids.add(gs);
                    }
                }
            }
        }

        ticks.add(snapshot);
    }

    // ─── 匯出 JSON 檔案 ─────────────────────────────────────────
    public void exportToFile(String filename) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename))) {
            writer.write("{\n");
            writer.write("  \"metadata\": {\n");
            writer.write("    \"scenarioName\": \"" + escapeJson(scenarioName) + "\",\n");
            writer.write("    \"dimensions\": {\n");
            writer.write("      \"height\": " + height + ",\n");
            writer.write("      \"rows\": " + rows + ",\n");
            writer.write("      \"cols\": " + cols + "\n");
            writer.write("    },\n");
            writer.write("    \"exportedAt\": \"" + java.time.Instant.now().toString() + "\"\n");
            writer.write("  },\n");

            // 靜態地圖元素
            writer.write("  \"staticMap\": [\n");
            for (int i = 0; i < staticElementsJson.size(); i++) {
                writer.write(staticElementsJson.get(i));
                if (i < staticElementsJson.size() - 1) writer.write(",\n");
            }
            writer.write("\n  ],\n");

            // 關鍵事件日誌
            writer.write("  \"eventLogs\": [\n");
            for (int i = 0; i < events.size(); i++) {
                writer.write(events.get(i).toJson("    "));
                if (i < events.size() - 1) writer.write(",\n");
            }
            writer.write("\n  ],\n");

            // 歷史模擬軌跡 (Time-series)
            writer.write("  \"timeline\": [\n");
            for (int i = 0; i < ticks.size(); i++) {
                writer.write(ticks.get(i).toJson("    "));
                if (i < ticks.size() - 1) writer.write(",\n");
            }
            writer.write("\n  ]\n");

            writer.write("}\n");
            System.err.println("[SUCCESS] Session JSON successfully exported to: " + filename);
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to export session JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // JSON 字串逸出處理
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
