package simulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

// ═══════════════════════════════════════════════════════════════════════════
// CustomRoomTextLoader — 把「貼上的icon地圖文字」直接轉成 CustomRoomParser.parse()
// 吃的 String[][][]，不用手動把每一格拆成陣列字面量。
//
// 文字格式(對應「預計的自定義輸入資料範例.txt」)：
//   - 每一「層樓」是連續幾行文字，每一行就是那一列(row)的所有格子，格子之間
//     不用任何分隔符號、直接黏在一起(例如"🧱🪜🧱⬜...")。
//   - 樓層跟樓層之間用一行以上的空白列分隔。
//   - 每一格看diff是「一個icon」還是一個ASCII字元(目前只有'E')，用Unicode
//     code point切，不是用char切——像🧱🪜🚧🚪🪵這些emoji在Java字串裡是
//     surrogate pair(佔2個char)，若直接用charAt/split逐char切會把一個icon
//     切成兩個亂碼字元；⚠️這種「基底符號+變體選擇符(U+FE0F)」組成的icon則要
//     額外把U+FE0F併回前一個token，否則會多切出一個看不見的字元。
// ═══════════════════════════════════════════════════════════════════════════
class CustomRoomTextLoader {

    static String[][][] loadFromFile(String path) {
        try {
            String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            return loadFromText(text);
        } catch (IOException e) {
            throw new RuntimeException("讀取自訂房型文字檔失敗: " + path, e);
        }
    }

    static String[][][] loadFromText(String text) {
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] rawLines = normalized.split("\n", -1);

        List<List<String[]>> floors = new ArrayList<>();
        List<String[]> currentFloor = new ArrayList<>();

        for (String line : rawLines) {
            if (line.trim().isEmpty()) {
                if (!currentFloor.isEmpty()) {
                    floors.add(currentFloor);
                    currentFloor = new ArrayList<>();
                }
                continue; // 空白列 = 樓層分隔線，不算進任何一層裡
            }
            currentFloor.add(splitToTokens(line));
        }
        if (!currentFloor.isEmpty()) floors.add(currentFloor);

        if (floors.isEmpty()) {
            throw new IllegalArgumentException("文字內容解析不出任何樓層，請確認格式(每層之間需要空白列分隔)");
        }

        String[][][] layout = new String[floors.size()][][];
        for (int z = 0; z < floors.size(); z++) {
            layout[z] = floors.get(z).toArray(new String[0][]);
        }
        return layout;
    }

    // 把一行文字切成一格一個token，正確處理surrogate pair emoji跟「基底+U+FE0F變體選擇符」組合。
    private static String[] splitToTokens(String line) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int len = line.length();
        while (i < len) {
            int cp = line.codePointAt(i);
            int charCount = Character.charCount(cp);
            int end = i + charCount;
            // ⚠️ = U+26A0(基底符號，BMP，1個char) + U+FE0F(variation selector，1個char)，
            // 兩個code point合起來才是「使用者眼中的同一個icon」，要多吃一個char。
            if (end < len && line.codePointAt(end) == 0xFE0F) {
                end += Character.charCount(0xFE0F);
            }
            tokens.add(line.substring(i, end));
            i = end;
        }
        return tokens.toArray(new String[0]);
    }
}
