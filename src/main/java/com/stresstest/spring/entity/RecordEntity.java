package com.stresstest.spring.entity;

import org.springframework.data.domain.Persistable;

import javax.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import java.sql.Timestamp;

/**
 * JPA Entity：對應 Oracle 資料表 jpa_records。
 * 每行 120 字元，分為 12 個欄位 (a~l)，每欄 10 字元。
 * ID 由應用程式透過 jpa_id_alloc 計數器表指派（無跳號）。
 */
@Entity
@Table(name = "jpa_records")
@DynamicUpdate  // 配合 Bytecode Enhancement 的 dirty tracking：只更新實際修改的欄位
public class RecordEntity implements Persistable<Long> {

    @Id
    private Long id;

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostPersist
    @PostLoad
    void markNotNew() { this.isNew = false; }

    @Column(name = "master_id")
    private Long masterId;

    @Column(name = "rowno")
    private Long rowno;

    @Column(name = "field_a", length = 10)
    private String fieldA;

    @Column(name = "field_b", length = 10)
    private String fieldB;

    @Column(name = "field_c", length = 10)
    private String fieldC;

    @Column(name = "field_d", length = 10)
    private String fieldD;

    @Column(name = "field_e", length = 10)
    private String fieldE;

    @Column(name = "field_f", length = 10)
    private String fieldF;

    @Column(name = "field_g", length = 10)
    private String fieldG;

    @Column(name = "field_h", length = 10)
    private String fieldH;

    @Column(name = "field_i", length = 10)
    private String fieldI;

    @Column(name = "field_j", length = 10)
    private String fieldJ;

    @Column(name = "field_k", length = 10)
    private String fieldK;

    @Column(name = "field_l", length = 10)
    private String fieldL;

    @Column(name = "download_time")
    private Timestamp downloadTime;

    public RecordEntity() {}

    public static RecordEntity fromLine(String line, long rowno, long masterId) {
        RecordEntity e = new RecordEntity();
        e.masterId = masterId;
        e.rowno = rowno;
        int width = 10;
        int len = line.length();
        e.fieldA = sub(line, 0, width, len);
        e.fieldB = sub(line, 1, width, len);
        e.fieldC = sub(line, 2, width, len);
        e.fieldD = sub(line, 3, width, len);
        e.fieldE = sub(line, 4, width, len);
        e.fieldF = sub(line, 5, width, len);
        e.fieldG = sub(line, 6, width, len);
        e.fieldH = sub(line, 7, width, len);
        e.fieldI = sub(line, 8, width, len);
        e.fieldJ = sub(line, 9, width, len);
        e.fieldK = sub(line, 10, width, len);
        e.fieldL = sub(line, 11, width, len);
        return e;
    }

    public String toCsvLine() {
        return id + "," + rowno + "," +
                fieldA + "," + fieldB + "," + fieldC + "," + fieldD + "," +
                fieldE + "," + fieldF + "," + fieldG + "," + fieldH + "," +
                fieldI + "," + fieldJ + "," + fieldK + "," + fieldL + "," +
                (downloadTime != null ? downloadTime.toString() : "");
    }

    private static String sub(String line, int idx, int width, int len) {
        int start = idx * width;
        int end = Math.min(start + width, len);
        return (start < len) ? line.substring(start, end) : "";
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMasterId() { return masterId; }
    public void setMasterId(Long masterId) { this.masterId = masterId; }
    public Long getRowno() { return rowno; }
    public void setRowno(Long rowno) { this.rowno = rowno; }
    public String getFieldA() { return fieldA; }
    public void setFieldA(String fieldA) { this.fieldA = fieldA; }
    public String getFieldB() { return fieldB; }
    public void setFieldB(String fieldB) { this.fieldB = fieldB; }
    public String getFieldC() { return fieldC; }
    public void setFieldC(String fieldC) { this.fieldC = fieldC; }
    public String getFieldD() { return fieldD; }
    public void setFieldD(String fieldD) { this.fieldD = fieldD; }
    public String getFieldE() { return fieldE; }
    public void setFieldE(String fieldE) { this.fieldE = fieldE; }
    public String getFieldF() { return fieldF; }
    public void setFieldF(String fieldF) { this.fieldF = fieldF; }
    public String getFieldG() { return fieldG; }
    public void setFieldG(String fieldG) { this.fieldG = fieldG; }
    public String getFieldH() { return fieldH; }
    public void setFieldH(String fieldH) { this.fieldH = fieldH; }
    public String getFieldI() { return fieldI; }
    public void setFieldI(String fieldI) { this.fieldI = fieldI; }
    public String getFieldJ() { return fieldJ; }
    public void setFieldJ(String fieldJ) { this.fieldJ = fieldJ; }
    public String getFieldK() { return fieldK; }
    public void setFieldK(String fieldK) { this.fieldK = fieldK; }
    public String getFieldL() { return fieldL; }
    public void setFieldL(String fieldL) { this.fieldL = fieldL; }
    public Timestamp getDownloadTime() { return downloadTime; }
    public void setDownloadTime(Timestamp downloadTime) { this.downloadTime = downloadTime; }
}
