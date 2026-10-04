package com.casting.shop.dto;

import lombok.Data;

/** 模具型壳新增/修改入参。 */
@Data
public class MoldRequest {

    private String moldNo;
    private String moldName;
    private String moldType;
    private Integer cavityCount;
    private Integer lifeCount;
    private Integer usedCount;
    private String fitMaterial;
    private String status;
    private String storageSlot;
    private String note;
}
