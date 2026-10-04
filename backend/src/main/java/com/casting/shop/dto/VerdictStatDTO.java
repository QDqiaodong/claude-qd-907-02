package com.casting.shop.dto;

import com.casting.shop.common.Dict;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ⑥：按判定结论（合格 / 返修 / 报废）统计检验单数量。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerdictStatDTO {

    private String verdict;
    private String verdictName;
    private String color;
    private long count;

    public VerdictStatDTO(String verdict, Long count) {
        this(verdict, Dict.verdictName(verdict), Dict.verdictColor(verdict), count == null ? 0L : count);
    }
}
