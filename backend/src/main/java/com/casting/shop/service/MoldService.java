package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Casting;
import com.casting.shop.domain.Mold;
import com.casting.shop.dto.MoldRequest;
import com.casting.shop.repository.CastingRepository;
import com.casting.shop.repository.MoldRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 模具型壳。
 *
 * <p>规则：模具编号唯一；型腔数必须为正；已用次数不得超过寿命次数；超寿命或已报废的模具禁止排产。</p>
 */
@Service
public class MoldService {

    private final MoldRepository moldRepository;
    private final CastingRepository castingRepository;

    public MoldService(MoldRepository moldRepository, CastingRepository castingRepository) {
        this.moldRepository = moldRepository;
        this.castingRepository = castingRepository;
    }

    public List<Mold> list() {
        return moldRepository.findAllByOrderByIdAsc();
    }

    public Mold get(Long id) {
        return moldRepository.findById(id)
                .orElseThrow(() -> new BizException("模具不存在：" + id));
    }

    @Transactional
    public Mold create(MoldRequest request) {
        if (!StringUtils.hasText(request.getMoldNo())) {
            throw new BizException("模具编号不能为空");
        }
        if (moldRepository.existsByMoldNo(request.getMoldNo())) {
            throw new BizException("模具编号已存在：" + request.getMoldNo());
        }
        if (!Dict.isValidMoldType(request.getMoldType())) {
            throw new BizException("型壳类型非法（四选一）：" + request.getMoldType());
        }
        Mold mold = new Mold();
        apply(mold, request);
        // 默认值放在这里，实体不带默认值
        if (mold.getUsedCount() == null) {
            mold.setUsedCount(0);
        }
        if (mold.getStatus() == null) {
            mold.setStatus("AVAILABLE");
        }
        if (!Dict.isValidMoldStatus(mold.getStatus())) {
            throw new BizException("模具状态非法：" + mold.getStatus());
        }
        if (request.getFitMaterial() != null && StringUtils.hasText(request.getFitMaterial())
                && !Dict.isValidMaterial(request.getFitMaterial())) {
            throw new BizException("适用材质非法，只能五选一：" + request.getFitMaterial());
        }
        checkCavityAndLife(mold);
        mold.setCreatedAt(LocalDateTime.now());
        return moldRepository.save(mold);
    }

    @Transactional
    public Mold update(Long id, MoldRequest request) {
        Mold mold = get(id);
        if (StringUtils.hasText(request.getMoldNo()) && !request.getMoldNo().equals(mold.getMoldNo())
                && moldRepository.existsByMoldNo(request.getMoldNo())) {
            throw new BizException("模具编号已存在：" + request.getMoldNo());
        }
        if (request.getMoldType() != null && !Dict.isValidMoldType(request.getMoldType())) {
            throw new BizException("型壳类型非法（四选一）：" + request.getMoldType());
        }
        if (request.getStatus() != null && !Dict.isValidMoldStatus(request.getStatus())) {
            throw new BizException("模具状态非法：" + request.getStatus());
        }
        if (request.getFitMaterial() != null && !Dict.isValidMaterial(request.getFitMaterial())) {
            throw new BizException("适用材质非法，只能五选一：" + request.getFitMaterial());
        }
        applyPartial(mold, request);
        checkCavityAndLife(mold);
        if (mold.isOverLife() && !"SCRAPPED".equals(mold.getStatus())) {
            mold.setStatus("SCRAPPED");
        }
        return moldRepository.save(mold);
    }

    @Transactional
    public void delete(Long id) {
        Mold mold = get(id);
        List<Casting> used = castingRepository.findByMoldIdOrderByIdAsc(id);
        if (!used.isEmpty()) {
            throw new BizException("模具 " + mold.getMoldNo() + " 已有 " + used.size() + " 张铸件卡引用，不能删除");
        }
        moldRepository.deleteById(id);
    }

    /** 排产一次：已用次数 +1，超寿命直接拦 */
    @Transactional
    public Mold consume(Long id) {
        Mold mold = get(id);
        if ("SCRAPPED".equals(mold.getStatus())) {
            throw new BizException("模具 " + mold.getMoldNo() + " 已报废，禁止排产");
        }
        if (mold.isOverLife()) {
            throw new BizException("模具 " + mold.getMoldNo() + " 已用 " + mold.getUsedCount()
                    + " / 寿命 " + mold.getLifeCount() + " 次，超寿命禁止排产");
        }
        mold.setUsedCount(mold.getUsedCount() + 1);
        mold.setStatus("IN_USE");
        if (mold.getUsedCount() >= mold.getLifeCount()) {
            mold.setStatus("SCRAPPED");
        }
        return moldRepository.save(mold);
    }

    /** 模具寿命进度环阵列：全部模具 + 按型壳类型的统计 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Mold> molds = moldRepository.findAllByOrderByIdAsc();
        data.put("molds", molds);
        data.put("types", moldRepository.statByMoldType());
        List<Map<String, Object>> typeMeta = new ArrayList<>();
        for (String type : Dict.MOLD_TYPES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", type);
            item.put("name", Dict.moldTypeName(type));
            item.put("count", molds.stream().filter(m -> type.equals(m.getMoldType())).count());
            typeMeta.add(item);
        }
        data.put("typeMeta", typeMeta);
        data.put("warnRate", Dict.MOLD_WARN_REMAIN_RATE);
        return data;
    }

    private void apply(Mold mold, MoldRequest request) {
        mold.setMoldNo(request.getMoldNo());
        mold.setMoldName(request.getMoldName());
        mold.setMoldType(request.getMoldType());
        mold.setCavityCount(request.getCavityCount());
        mold.setLifeCount(request.getLifeCount());
        mold.setUsedCount(request.getUsedCount());
        mold.setFitMaterial(request.getFitMaterial());
        mold.setStatus(request.getStatus());
        mold.setStorageSlot(request.getStorageSlot());
        mold.setNote(request.getNote());
    }

    private void applyPartial(Mold mold, MoldRequest request) {
        ifNotNull(request.getMoldNo(), mold::setMoldNo);
        ifNotNull(request.getMoldName(), mold::setMoldName);
        ifNotNull(request.getMoldType(), mold::setMoldType);
        ifNotNull(request.getCavityCount(), mold::setCavityCount);
        ifNotNull(request.getLifeCount(), mold::setLifeCount);
        ifNotNull(request.getUsedCount(), mold::setUsedCount);
        ifNotNull(request.getFitMaterial(), mold::setFitMaterial);
        ifNotNull(request.getStatus(), mold::setStatus);
        ifNotNull(request.getStorageSlot(), mold::setStorageSlot);
        ifNotNull(request.getNote(), mold::setNote);
    }

    private void checkCavityAndLife(Mold mold) {
        if (mold.getCavityCount() == null || mold.getCavityCount() <= 0) {
            throw new BizException("型腔数必须大于 0");
        }
        if (mold.getLifeCount() == null || mold.getLifeCount() <= 0) {
            throw new BizException("寿命次数必须大于 0");
        }
        int used = mold.getUsedCount() == null ? 0 : mold.getUsedCount();
        if (used < 0) {
            throw new BizException("已用次数不能为负数");
        }
        if (used > mold.getLifeCount()) {
            throw new BizException("已用次数 " + used + " 超过寿命次数 " + mold.getLifeCount()
                    + "（模具 " + mold.getMoldNo() + "）");
        }
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
