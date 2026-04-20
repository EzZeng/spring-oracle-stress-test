package com.stresstest.spring.model;

/**
 * 資料模型：代表檔案中的一行。
 * 每行 120 字元，分為 12 個欄位 (a~l)，每個欄位 10 字元。
 */
public class RowRecord {

    private static final int FIELD_WIDTH = 10;
    private static final int FIELD_COUNT = 12;

    private final String[] fields;

    private RowRecord(String[] fields) {
        this.fields = fields;
    }

    public static RowRecord parse(String line) {
        String[] fields = new String[FIELD_COUNT];
        for (int i = 0; i < FIELD_COUNT; i++) {
            int start = i * FIELD_WIDTH;
            int end = Math.min(start + FIELD_WIDTH, line.length());
            fields[i] = (start < line.length()) ? line.substring(start, end) : "";
        }
        return new RowRecord(fields);
    }

    public String getField(int index) {
        return fields[index];
    }

    public String getA() { return fields[0]; }
    public String getB() { return fields[1]; }
    public String getC() { return fields[2]; }
    public String getD() { return fields[3]; }
    public String getE() { return fields[4]; }
    public String getF() { return fields[5]; }
    public String getG() { return fields[6]; }
    public String getH() { return fields[7]; }
    public String getI() { return fields[8]; }
    public String getJ() { return fields[9]; }
    public String getK() { return fields[10]; }
    public String getL() { return fields[11]; }

    public String[] getAllFields() {
        return fields;
    }
}
