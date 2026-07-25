package simulator;

import java.util.Scanner;

class sim {
    private static final Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        Simulator s = new Simulator();

        while (true) {
            System.out.println("\n==========================================");
            System.out.println("===        模擬器主控制台系統          ===");
            System.out.println("==========================================");
            System.out.println("1. 隨機/範圍取值模式");
            System.out.println("2. 文件導入模式");
            System.out.println("0. 結束程式");

            int mode = readIntOption("請選擇輸入模式", 0, 2);

            if (mode == 0) {
                System.out.println("已退出系統。");
                break;
            }

            boolean completed = false;
            if (mode == 1) {
                completed = handleModeOne(s);
            } else if (mode == 2) {
                completed = handleModeTwo(s);
            }

            if (completed) {
                System.out.println("\n[系統通知] 本次模擬任務執行完畢，返還主功能表。");
            }
        }

        scanner.close();
    }

    /**
     * 處理模式 1：隨機/範圍取值
     * @return true 代表完整執行完成，false 代表使用者中途選擇返回
     */
    private static boolean handleModeOne(Simulator s) {
        System.out.println("\n--- [模式 1：隨機/範圍取值] ---");
        System.out.println("(提示：在此模式的任何選單輸入 0 可隨時返回主功能表)");

        int count = readIntWithMin("請輸入資料數量 (大樓建築數量) [輸入 0 返回]", 0);
        if (count == 0) return false;

        Data[] data = new Data[count];
        System.out.println("\n請依序輸入每筆資料的參數：");

        for (int i = 0; i < count; i++) {
            System.out.printf("\n>>> 配置第 %d / %d 筆大樓資料 <<<\n", i + 1, count);
            
            int hMin = readIntWithMin("請輸入高度最小值 (hMin)", 1);
            int hMax = readIntWithMin("請輸入高度最大值 (hMax)", hMin)+1; // 確保最大值大於等於最小值

            int wMin = readIntWithMin("請輸入寬度最小值 (wMin)", 1);
            int wMax = readIntWithMin("請輸入寬度最大值 (wMax)", wMin)+1;

            int lMin = readIntWithMin("請輸入長度最小值 (lMin)", 1);
            int lMax = readIntWithMin("請輸入長度最大值 (lMax)", lMin)+1;

            FireCause cause = selectFireCauseMenu();
            if (cause == null) {
                System.out.println(">> 已取消當前設定，返回主功能表。");
                return false;
            }

            data[i] = new Data(
                new Range(hMin, hMax),
                new Range(wMin, wMax),
                new Range(lMin, lMax),
                cause
            );
        }

        int workCount = readIntWithMin("請輸入每個建築的模擬次數 (work 數) [輸入 0 返回]", 0);
        if (workCount == 0) return false;

        Boolean outputJson = readBooleanOption("是否輸出 JSON 檔案？(注意：大型樓型可能導致檔過大或 Heap 溢位)");
        if (outputJson == null) return false; // 使用者選擇返回

        s.sessionOutput = outputJson;
        System.out.println("\n[系統] 開始執行模擬，請稍候...");
        s.work(data, workCount);
        return true;
    }

    /**
     * 處理模式 2：文件導入
     * @return true 代表完整執行完成，false 代表使用者中途選擇返回
     */
    private static boolean handleModeTwo(Simulator s) {
        System.out.println("\n--- [模式 2：文件導入] ---");
        System.out.println("(提示：輸入 0 可隨時返回主功能表)");

        System.out.print("請輸入資料檔案絕對路徑 (例如: C:\\path\\to\\file.txt) [輸入 0 返回]: ");
        String filePath = scanner.nextLine().trim();

        if ("0".equals(filePath)) {
            return false;
        }

        FireCause cause = selectFireCauseMenu();
        if (cause == null) return false;

        int workCount = readIntWithMin("請輸入每個建築的模擬次數 (work 數) [輸入 0 返回]", 0);
        if (workCount == 0) return false;

        Boolean outputJson = readBooleanOption("是否輸出 JSON 檔案？");
        if (outputJson == null) return false;

        s.sessionOutput = outputJson;
        System.out.println("\n[系統] 開始讀取檔案並執行模擬...");
        s.workCustomRoom(filePath, cause, workCount);
        return true;
    }

    // =========================================================================
    //                            輸入保護輔助工具函式
    // =========================================================================

    /**
     * 讀取選單選項（限制範圍 min ~ max）
     */
    private static int readIntOption(String prompt, int min, int max) {
        while (true) {
            System.out.printf("%s (%d~%d): ", prompt, min, max);
            try {
                int input = Integer.parseInt(scanner.nextLine().trim());
                if (input >= min && input <= max) {
                    return input;
                }
                System.out.printf("[錯誤] 輸入超出範圍，請輸入數字 %d 到 %d 之間的數值！\n", min, max);
            } catch (NumberFormatException e) {
                System.out.println("[錯誤] 輸入格式不正確，請勿輸入非數字字元！");
            }
        }
    }

    /**
     * 讀取整數並限定最小值
     */
    private static int readIntWithMin(String prompt, int minLimit) {
        while (true) {
            System.out.printf("%s (≥ %d): ", prompt, minLimit);
            try {
                int input = Integer.parseInt(scanner.nextLine().trim());
                if (input >= minLimit) {
                    return input;
                }
                System.out.printf("[錯誤] 輸入數值不能小於 %d！\n", minLimit);
            } catch (NumberFormatException e) {
                System.out.println("[錯誤] 輸入格式不正確，請輸入有效的整數！");
            }
        }
    }

    /**
     * 獨立的火災原因選擇選單（回傳 null 代表使用者輸入 0 選擇返回）
     */
    private static FireCause selectFireCauseMenu() {
        System.out.println("\n--- 請選擇失火原因 ---");
        System.out.println("1. 意外 (ACCIDENTAL)");
        System.out.println("2. 縱火 (ARSON)");
        System.out.println("3. 化學 (CHEMICAL)");
        System.out.println("4. 電氣 (ELECTRICAL)");
        System.out.println("0. 返回上一頁");

        int choice = readIntOption("請選擇項目", 0, 4);
        switch (choice) {
            case 1: return FireCause.ACCIDENTAL;
            case 2: return FireCause.ARSON;
            case 3: return FireCause.CHEMICAL;
            case 4: return FireCause.ELECTRICAL;
            case 0: 
            default:
                return null;
        }
    }

    /**
     * 安全讀取 Boolean 設定，支援返回 0
     * @return Boolean 物件，若使用者選擇 0 則回傳 null
     */
    private static Boolean readBooleanOption(String prompt) {
        while (true) {
            System.out.printf("%s (1: 是/True, 2: 否/False, 0: 返回): ", prompt);
            try {
                int choice = Integer.parseInt(scanner.nextLine().trim());
                if (choice == 1) return true;
                if (choice == 2) return false;
                if (choice == 0) return null;
                System.out.println("[錯誤] 請輸入 1、2 或 0！");
            } catch (NumberFormatException e) {
                System.out.println("[錯誤] 請輸入有效的數字選項！");
            }
        }
    }
}