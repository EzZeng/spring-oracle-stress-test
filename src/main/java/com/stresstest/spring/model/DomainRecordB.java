package com.stresstest.spring.model;

/**
 * Domain-B 寫入用的資料模型。
 * 對應 approved_file_detail 表的一筆資料（與 DomainRecordA 結構相同）。
 */
public class DomainRecordB {

    private long caseId;
    private long masterId;
    private String fieldA, fieldB, fieldC, fieldD, fieldE, fieldF;
    private String fieldG, fieldH, fieldI, fieldJ, fieldK, fieldL;

    public DomainRecordB() {}

    public DomainRecordB(long caseId, long masterId,
                         String fieldA, String fieldB, String fieldC, String fieldD,
                         String fieldE, String fieldF, String fieldG, String fieldH,
                         String fieldI, String fieldJ, String fieldK, String fieldL) {
        this.caseId = caseId;
        this.masterId = masterId;
        this.fieldA = fieldA; this.fieldB = fieldB; this.fieldC = fieldC; this.fieldD = fieldD;
        this.fieldE = fieldE; this.fieldF = fieldF; this.fieldG = fieldG; this.fieldH = fieldH;
        this.fieldI = fieldI; this.fieldJ = fieldJ; this.fieldK = fieldK; this.fieldL = fieldL;
    }

    public long getCaseId() { return caseId; }
    public void setCaseId(long caseId) { this.caseId = caseId; }
    public long getMasterId() { return masterId; }
    public void setMasterId(long masterId) { this.masterId = masterId; }
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
}
