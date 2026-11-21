import com.nlf.calendar.Lunar;

/**
 * 一次性生成 app/src/main/assets/calendar/festivals_2026_2036.json。
 *
 * 用法（在 tools/genfestivals 目录）：
 *   javac -encoding UTF-8 -cp lunar-1.7.8.jar -d out GenFestivals.java
 *   java -cp "lunar-1.7.8.jar;out" GenFestivals > ../../app/src/main/assets/calendar/festivals_2026_2036.json
 *
 * 为什么不在构建里跑：会把私有库挂到 app/build.gradle 上（计划书 §4 V2.1.3 明确禁止），
 * 且每次构建都跑一遍纯属浪费。这里一次性生成、JSON 提交入库，App 运行时只读 JSON。
 *
 * 为什么必须用库：农历是天文历法，手写必错。上一版手写的节日表整整错了一年
 * （把 2026 春节写成 2026-02-06，实际是 2026-02-17）。
 */
public final class GenFestivals {

    /** 覆盖年份。计划书要求 2026–2036。 */
    private static final int FROM_YEAR = 2026;
    private static final int TO_YEAR = 2036;

    /** 农历节日：名称 + 农历月 + 农历日。 */
    private static final String[][] LUNAR_FESTIVALS = {
            {"春节", "1", "1"},
            {"元宵", "1", "15"},
            {"端午", "5", "5"},
            {"七夕", "7", "7"},
            {"中秋", "8", "15"},
            {"重阳", "9", "9"},
    };

    /** 公历节日：名称 + 月 + 日（年份无关，每年同一月日）。 */
    private static final String[][] SOLAR_FESTIVALS = {
            {"元旦", "1", "1"},
            {"劳动节", "5", "1"},
            {"国庆", "10", "1"},
    };

    public static void main(String[] args) {
        verifyKnownDates();

        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");

        sb.append("  \"lunar\": [\n");
        for (int i = 0; i < LUNAR_FESTIVALS.length; i++) {
            String name = LUNAR_FESTIVALS[i][0];
            int month = Integer.parseInt(LUNAR_FESTIVALS[i][1]);
            int day = Integer.parseInt(LUNAR_FESTIVALS[i][2]);

            sb.append("    {\"name\": \"").append(name).append("\", \"dates\": [");
            for (int year = FROM_YEAR; year <= TO_YEAR; year++) {
                String ymd = Lunar.fromYmd(year, month, day).getSolar().toYmd();
                if (year > FROM_YEAR) {
                    sb.append(", ");
                }
                sb.append('"').append(ymd).append('"');
            }
            sb.append("]}");
            sb.append(i == LUNAR_FESTIVALS.length - 1 ? "\n" : ",\n");
        }
        sb.append("  ],\n");

        sb.append("  \"solar\": [\n");
        for (int i = 0; i < SOLAR_FESTIVALS.length; i++) {
            sb.append("    {\"name\": \"").append(SOLAR_FESTIVALS[i][0]).append("\", ")
                    .append("\"month\": ").append(Integer.parseInt(SOLAR_FESTIVALS[i][1])).append(", ")
                    .append("\"day\": ").append(Integer.parseInt(SOLAR_FESTIVALS[i][2])).append("}");
            sb.append(i == SOLAR_FESTIVALS.length - 1 ? "\n" : ",\n");
        }
        sb.append("  ]\n");

        sb.append("}\n");
        System.out.print(sb);
    }

    /**
     * 自校验：先算几个已知正确的日期，不对就立刻失败。
     *
     * 防的是「API 用错但没报错」——比如把公历当农历传进去，生成的表会全错但看起来很正常。
     */
    private static void verifyKnownDates() {
        check("春节", 2026, 1, 1, "2026-02-17");
        check("春节", 2027, 1, 1, "2027-02-06");
        check("春节", 2028, 1, 1, "2028-01-26");
        check("元宵", 2026, 1, 15, "2026-03-03");
        check("端午", 2026, 5, 5, "2026-06-19");
        check("七夕", 2026, 7, 7, "2026-08-19");
        check("中秋", 2026, 8, 15, "2026-09-25");
        check("重阳", 2026, 9, 9, "2026-10-18");
    }

    private static void check(String name, int lunarYear, int lunarMonth, int lunarDay, String expected) {
        String actual = Lunar.fromYmd(lunarYear, lunarMonth, lunarDay).getSolar().toYmd();
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    name + " " + lunarYear + " 期望 " + expected + "，实际 " + actual);
        }
    }

    private GenFestivals() {
    }
}