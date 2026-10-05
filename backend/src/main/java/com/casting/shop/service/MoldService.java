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

    /**
     * 建铸件卡排产前占用模具：先对模具行加排他锁，再按锁内最新数据做可排产校验。
     *
     * <p>同一模具的并发建卡在此锁上串行：先到事务提交（末次寿命被扣完、模具转报废）后，
     * 后到事务才能拿到锁，并读到已提交的 used_count / status，从而被下面的校验明确挡回，
     * 不会出现两笔排产都判断「还剩一次」的双占。</p>
     *
     * <p>本方法只加锁与校验，真正扣寿命在同事务内由
     * {@link #consumeLocked(Mold, String)} 的原子 UPDATE 完成；
     * 校验不满足时抛 {@link BizException}，调用方整个事务回滚，模具次数不变。</p>
     *
     * @param material 铸件材质，用于校验模具适用材质；为空表示不做材质比对
     */
    @Transactional
    public Mold lockForScheduling(Long moldId, String material) {
        Long lockedId = moldRepository.lockRowById(moldId);
        if (lockedId == null) {
            throw new BizException("模具不存在：" + moldId);
        }
        // 加锁后再加载：必须读到锁内最新的已提交数据，不能用加锁前的旧快照
        Mold mold = moldRepository.findById(moldId).orElseThrow(() -> new BizException("模具不存在：" + moldId));
        checkSchedulable(mold);
        if (StringUtils.hasText(mold.getFitMaterial())
                && !mold.getFitMaterial().equals(material)) {
            throw new BizException("模具 " + mold.getMoldNo() + " 的适用材质为「"
                    + Dict.materialName(mold.getFitMaterial()) + "」，不能排产材质「"
                    + Dict.materialName(material) + "」的铸件");
        }
        return mold;
    }

    /** 可排产校验：已报废 / 寿命已耗尽都不允许再排产（调用前模具行应已持有行锁） */
    private void checkSchedulable(Mold mold) {
        if ("SCRAPPED".equals(mold.getStatus())) {
            throw new BizException("模具 " + mold.getMoldNo() + " 已报废（寿命 "
                    + mold.getLifeCount() + " 次已用完），禁止再排产，请刷新页面查看模具最新状态");
        }
        if (mold.isOverLife()) {
            throw new BizException("模具 " + mold.getMoldNo() + " 已用 " + mold.getUsedCount()
                    + " / 寿命 " + mold.getLifeCount() + " 次，剩余寿命为 0，禁止再排产，请刷新页面查看最新状态");
        }
    }

    /**
     * 排产一次：原子占用一次模具寿命。
     *
     * <p>扣减是一条带剩余寿命条件的 UPDATE（{@link MoldRepository#consumeOnce}），
     * 由数据库保证同一模具的并发排产最多成功一次；末次寿命扣减成功时模具当次转为 SCRAPPED，
     * 立即进入不可再排产状态。竞争失败（末次寿命已被先到的排产占用）会得到明确业务失败。</p>
     */
    @Transactional
    public Mold consume(Long id) {
        Long lockedId = moldRepository.lockRowById(id);
        if (lockedId == null) {
            throw new BizException("模具不存在：" + id);
        }
        Mold before = moldRepository.findById(id).orElseThrow(() -> new BizException("模具不存在：" + id));
        return consumeLocked(before, null);
    }

    /**
     * 在调用方已持有模具行锁（见 {@link #lockForScheduling}）的前提下原子扣一次寿命。
     * 条件 UPDATE 返回 0 只会发生在并发竞争失败（最后一次寿命已被别的事务占用），
     * 此时抛出业务异常让整个建卡事务回滚——卡片不会落库，次数也不会多扣。
     *
     * @param mold       锁内加载的模具实体
     * @param businessNo 关联业务单号（铸件编号），仅用于失败提示，可为空
     */
    @Transactional
    public Mold consumeLocked(Mold mold, String businessNo) {
        int updated = moldRepository.consumeOnce(mold.getId());
        if (updated == 0) {
            String suffix = businessNo == null ? "" : "（铸件卡 " + businessNo + " 未建立）";
            throw new BizException("模具 " + mold.getMoldNo() + " 的剩余寿命刚被其他排产占用（寿命 "
                    + mold.getLifeCount() + " 次已用完），本笔排产失败，请刷新页面查看最新结果" + suffix);
        }
        // consumeOnce 带 clearAutomatically，持久化上下文已清空，重新读取扣减后的真实状态
        return moldRepository.findById(mold.getId()).orElseThrow();
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
