package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Casting;
import com.casting.shop.domain.Inspection;
import com.casting.shop.domain.ReworkCorrection;
import com.casting.shop.domain.ReworkEvent;
import com.casting.shop.domain.ReworkOrder;
import com.casting.shop.domain.ReworkRound;
import com.casting.shop.dto.CorrectionRequest;
import com.casting.shop.dto.ReinspectRequest;
import com.casting.shop.dto.ReworkCompleteRequest;
import com.casting.shop.repository.CastingRepository;
import com.casting.shop.repository.DefectRepository;
import com.casting.shop.repository.InspectionRepository;
import com.casting.shop.repository.ReworkCorrectionRepository;
import com.casting.shop.repository.ReworkEventRepository;
import com.casting.shop.repository.ReworkOrderRepository;
import com.casting.shop.repository.ReworkRoundRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 返修处置与复检闭环。
 *
 * <p>一条铸件只能有一张返修单（casting_id 唯一约束兜底）；返修单下按轮次推进：</p>
 * <ol>
 *   <li>首次检验判返修 → 建返修单 + 第 1 轮（缺陷快照=首次检验单），铸件进「返修中」独立队列；</li>
 *   <li>登记返修完成（责任人 / 处置方式 / 完成时间 / 处置说明）→ 铸件转「待复检」；</li>
 *   <li>复检只能对刚完成的这一轮发起：合格→入库闭环；报废→永久终止；
 *       再返修→旧轮原样保留，生成下一轮待办，新一轮快照=本轮复检单；</li>
 *   <li>复检单创建前允许撤回本轮完成结果重填；复检单一旦创建，处置与结论全部冻结，
 *       误填只能 {@link #correct 追加更正说明}，任何历史字段都不回写。</li>
 * </ol>
 *
 * <p><b>并发策略</b>：所有写动作在同一事务里按固定顺序加行锁
 * （返修单 FOR UPDATE → 当前轮 FOR UPDATE，必要时再锁铸件行），
 * 现场两人同时点「完成」或「复检」时数据库把他们串行化：后到的事务拿到锁后
 * 按状态判定必然失败，并在报错里点出先操作者，刷新详情即可看到已生效的轮次，
 * 不会产生两张待办，也不会覆盖缺陷快照。</p>
 */
@Service
public class ReworkService {

    private static final DateTimeFormatter TIP_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final ReworkOrderRepository orderRepository;
    private final ReworkRoundRepository roundRepository;
    private final ReworkCorrectionRepository correctionRepository;
    private final ReworkEventRepository eventRepository;
    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;
    private final CastingRepository castingRepository;
    private final DefectAssembler defectAssembler;

    public ReworkService(ReworkOrderRepository orderRepository,
                         ReworkRoundRepository roundRepository,
                         ReworkCorrectionRepository correctionRepository,
                         ReworkEventRepository eventRepository,
                         InspectionRepository inspectionRepository,
                         DefectRepository defectRepository,
                         CastingRepository castingRepository,
                         DefectAssembler defectAssembler) {
        this.orderRepository = orderRepository;
        this.roundRepository = roundRepository;
        this.correctionRepository = correctionRepository;
        this.eventRepository = eventRepository;
        this.inspectionRepository = inspectionRepository;
        this.defectRepository = defectRepository;
        this.castingRepository = castingRepository;
        this.defectAssembler = defectAssembler;
    }

    // ================= 首次检验判返修时由 InspectionService 调用 =================

    /**
     * 首次检验判返修：建立返修单与第 1 轮待办。调用方已持有铸件行锁并处于事务中。
     * 一件铸件至多一张返修单——这里再查一次（唯一约束是最后一道防线）。
     */
    @Transactional
    public ReworkOrder openFromInspection(Casting casting, Inspection firstInspection) {
        if (orderRepository.existsByCastingId(casting.getId())) {
            throw new BizException("铸件 " + casting.getCastingNo() + " 已存在返修单，不能重复建立返修队列");
        }
        LocalDateTime now = LocalDateTime.now();
        ReworkOrder order = new ReworkOrder();
        order.setReworkNo("RW-" + firstInspection.getInspectNo().replaceFirst("^I-", ""));
        order.setCastingId(casting.getId());
        order.setCastingNo(casting.getCastingNo());
        order.setSnapshotInspectId(firstInspection.getId());
        order.setStage("REWORKING");
        order.setCurrentRound(1);
        order.setCreatedAt(now);
        ReworkOrder saved = orderRepository.save(order);

        ReworkRound round = new ReworkRound();
        round.setReworkId(saved.getId());
        round.setRoundNo(1);
        round.setSourceInspectId(firstInspection.getId());
        round.setRoundStatus("PENDING");
        round.setCreatedAt(now);
        roundRepository.save(round);

        casting.setStatus("REWORK");
        castingRepository.save(casting);

        recordEvent(saved.getId(), 1, "CREATED", firstInspection.getInspector(),
                "首次检验单 " + firstInspection.getInspectNo() + " 判返修，进入返修队列（缺陷快照已留存）", now);
        return saved;
    }

    // ================= 看板 / 列表（按返修阶段定位积压） =================

    public Map<String, Object> board(String stage) {
        Map<String, Object> data = new LinkedHashMap<>();
        List<ReworkOrder> orders = StringUtils.hasText(stage)
                ? orderRepository.findByStageOrderByIdDesc(stage)
                : orderRepository.findAllByOrderByIdDesc();
        data.put("orders", orders);

        Map<Long, Casting> castingMap = new LinkedHashMap<>();
        for (Casting c : castingRepository.listDerived()) {
            castingMap.put(c.getId(), c);
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        for (ReworkOrder o : orders) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("order", o);
            card.put("casting", castingMap.get(o.getCastingId()));
            ReworkRound current = roundRepository
                    .findByReworkIdAndRoundNo(o.getId(), o.getCurrentRound()).orElse(null);
            card.put("currentRound", current);
            card.put("snapshotInspection", inspectionRepository.findById(o.getSnapshotInspectId()).orElse(null));
            cards.add(card);
        }
        data.put("cards", cards);

        List<Map<String, Object>> stageStats = new ArrayList<>();
        for (String s : Dict.REWORK_STAGES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stage", s);
            item.put("name", Dict.reworkStageName(s));
            item.put("color", Dict.reworkStageColor(s));
            item.put("count", orderRepository.countByStage(s));
            stageStats.add(item);
        }
        data.put("stageStats", stageStats);

        List<Map<String, Object>> methods = new ArrayList<>();
        for (String m : Dict.REWORK_METHODS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", m);
            item.put("name", Dict.reworkMethodName(m));
            methods.add(item);
        }
        data.put("methods", methods);
        return data;
    }

    // ================= 详情：首次缺陷 → 每轮处置 → 复检结论（时间线） =================

    public ReworkOrder getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BizException("返修单不存在：" + id));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        ReworkOrder order = getOrder(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        data.put("casting", castingRepository.findById(order.getCastingId()).orElse(null));
        data.put("snapshot", inspectionView(order.getSnapshotInspectId()));

        List<Map<String, Object>> rounds = new ArrayList<>();
        for (ReworkRound round : roundRepository.findByReworkIdOrderByRoundNoAsc(order.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("round", round);
            item.put("source", inspectionView(round.getSourceInspectId()));
            item.put("reinspection", round.getReinspectId() == null
                    ? null : inspectionView(round.getReinspectId()));
            rounds.add(item);
        }
        data.put("rounds", rounds);
        data.put("corrections", correctionRepository.findByReworkIdOrderByIdAsc(order.getId()));
        data.put("events", eventRepository.findByReworkIdOrderByIdAsc(order.getId()));
        return data;
    }

    private Map<String, Object> inspectionView(Long inspectId) {
        Inspection inspection = inspectionRepository.findById(inspectId).orElse(null);
        if (inspection == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("inspection", inspection);
        view.put("defects", defectRepository.findByInspectIdOrderByIdAsc(inspectId));
        return view;
    }

    // ================= 登记返修完成 =================

    @Transactional
    public ReworkRound complete(Long reworkId, ReworkCompleteRequest request) {
        ReworkOrder order = orderRepository.lockById(reworkId)
                .orElseThrow(() -> new BizException("返修单不存在：" + reworkId));
        ReworkRound round = lockCurrentRound(order);

        if (!"REWORKING".equals(order.getStage()) || !"PENDING".equals(round.getRoundStatus())) {
            // 后到的重复提交：直接报出已生效结果与操作者，刷新即可看到，不允许覆盖
            throw new BizException("第 " + round.getRoundNo() + " 轮返修已由「" + nullToDash(round.getCompletedBy())
                    + "」于 " + fmt(round.getCompletedAt()) + " 登记完成，结论以系统记录为准，请勿重复提交");
        }
        if (!Dict.isValidReworkMethod(request.getMethod())) {
            throw new BizException("处置方式非法，只能五选一：" + request.getMethod());
        }
        if (!StringUtils.hasText(request.getResponsiblePerson())) {
            throw new BizException("返修责任人不能为空");
        }
        if (!StringUtils.hasText(request.getNote())) {
            throw new BizException("处置说明不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        round.setReworkMethod(request.getMethod());
        round.setResponsiblePerson(request.getResponsiblePerson().trim());
        round.setReworkNote(request.getNote().trim());
        round.setCompletedBy(StringUtils.hasText(request.getOperator()) ? request.getOperator().trim() : null);
        round.setCompletedAt(now);
        round.setRoundStatus("COMPLETED");
        roundRepository.save(round);

        order.setStage("REINSPECTING");
        orderRepository.save(order);
        Casting casting = lockCasting(order);
        casting.setStatus("REINSPECT");
        castingRepository.save(casting);

        recordEvent(order.getId(), round.getRoundNo(), "COMPLETED", round.getCompletedBy(),
                "责任人 " + round.getResponsiblePerson() + " · " + Dict.reworkMethodName(request.getMethod())
                        + " · 完成 " + fmt(now) + " · " + round.getReworkNote(), now);
        return round;
    }

    // ================= 撤回本轮完成结果（复检单创建前的唯一修正入口） =================

    @Transactional
    public ReworkRound withdraw(Long reworkId, String operator) {
        ReworkOrder order = orderRepository.lockById(reworkId)
                .orElseThrow(() -> new BizException("返修单不存在：" + reworkId));
        ReworkRound round = lockCurrentRound(order);

        if (round.getReinspectId() != null) {
            throw new BizException("第 " + round.getRoundNo() + " 轮复检单已创建，处置结果已成为历史证据，"
                    + "不能撤回或改写；如属误填，请追加更正说明");
        }
        if (!"COMPLETED".equals(round.getRoundStatus())) {
            throw new BizException("第 " + round.getRoundNo() + " 轮当前为「"
                    + Dict.roundStatusName(round.getRoundStatus()) + "」，没有可撤回的完成结果");
        }

        round.setReworkMethod(null);
        round.setResponsiblePerson(null);
        round.setReworkNote(null);
        round.setCompletedBy(null);
        round.setCompletedAt(null);
        round.setRoundStatus("PENDING");
        roundRepository.save(round);

        order.setStage("REWORKING");
        orderRepository.save(order);
        Casting casting = lockCasting(order);
        casting.setStatus("REWORK");
        castingRepository.save(casting);

        LocalDateTime now = LocalDateTime.now();
        recordEvent(order.getId(), round.getRoundNo(), "WITHDRAWN", operator,
                "撤回本轮返修完成结果，回到待返修重新登记（原处置内容不作历史保留）", now);
        return round;
    }

    // ================= 发起复检（合格 / 再返修 / 报废） =================

    @Transactional
    public Map<String, Object> reinspect(Long reworkId, ReinspectRequest request) {
        ReworkOrder order = orderRepository.lockById(reworkId)
                .orElseThrow(() -> new BizException("返修单不存在：" + reworkId));
        ReworkRound round = lockCurrentRound(order);

        if (round.getReinspectId() != null) {
            throw new BizException("第 " + round.getRoundNo() + " 轮复检已由检验员「" + nullToDash(round.getReinspector())
                    + "」于 " + fmt(round.getReinspectTime()) + " 完成，结论为「"
                    + Dict.verdictName(round.getReinspectVerdict()) + "」，请勿重复发起复检");
        }
        if (!"REINSPECTING".equals(order.getStage()) || !"COMPLETED".equals(round.getRoundStatus())) {
            throw new BizException("第 " + round.getRoundNo() + " 轮返修尚未登记完成，不能发起复检");
        }
        if (!Dict.isValidVerdict(request.getVerdict())) {
            throw new BizException("复检结论只能三选一（PASS / REWORK / SCRAP）：" + request.getVerdict());
        }
        List<com.casting.shop.dto.DefectRequest> defects =
                request.getDefects() == null ? List.of() : request.getDefects();
        defectAssembler.requireDefectsForVerdict(request.getVerdict(), defects);

        // 复检单是一张全新的检验单：旧检验单仍按原方式查看，新单即本轮结论、也是下一轮的缺陷快照
        LocalDateTime now = LocalDateTime.now();
        Inspection inspection = new Inspection();
        inspection.setInspectNo("IR-" + String.format("%04d", order.getId()) + "-R" + round.getRoundNo());
        inspection.setCastingId(order.getCastingId());
        inspection.setCastingNo(order.getCastingNo());
        inspection.setInspector(StringUtils.hasText(request.getInspector()) ? request.getInspector().trim() : null);
        inspection.setVerdict(request.getVerdict());
        inspection.setRemark(request.getRemark());
        inspection.setInspectTime(now);
        Inspection savedInspection = inspectionRepository.save(inspection);
        int defectCount = defectAssembler.saveAll(savedInspection.getId(), defects);
        savedInspection.setDefectCount(defectCount);
        inspectionRepository.save(savedInspection);

        round.setReinspectId(savedInspection.getId());
        round.setReinspectVerdict(request.getVerdict());
        round.setReinspector(savedInspection.getInspector());
        round.setReinspectTime(now);
        round.setRoundStatus(request.getVerdict());
        roundRepository.save(round);

        Casting casting = lockCasting(order);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inspection", savedInspection);
        result.put("round", round);

        switch (request.getVerdict()) {
            case "PASS" -> {
                order.setStage("STORED");
                order.setClosedAt(now);
                orderRepository.save(order);
                casting.setStatus("STORED");
                castingRepository.save(casting);
                recordEvent(order.getId(), round.getRoundNo(), "REINSPECTED", savedInspection.getInspector(),
                        "复检合格（复检单 " + savedInspection.getInspectNo() + "）", now);
                recordEvent(order.getId(), round.getRoundNo(), "CLOSED_STORED", savedInspection.getInspector(),
                        "返修闭环结束，铸件入库", now);
                result.put("closed", "STORED");
            }
            case "SCRAP" -> {
                order.setStage("SCRAPPED");
                order.setClosedAt(now);
                orderRepository.save(order);
                casting.setStatus("SCRAPPED");
                castingRepository.save(casting);
                recordEvent(order.getId(), round.getRoundNo(), "REINSPECTED", savedInspection.getInspector(),
                        "复检判废（复检单 " + savedInspection.getInspectNo() + "，缺陷 " + defectCount + " 条）", now);
                recordEvent(order.getId(), round.getRoundNo(), "CLOSED_SCRAP", savedInspection.getInspector(),
                        "返修链永久终止，铸件不可再入库", now);
                result.put("closed", "SCRAPPED");
            }
            case "REWORK" -> {
                // 旧轮原样保留（处置 + 复检结论都已冻结），生成下一轮待办，快照指向本轮复检单
                int nextNo = round.getRoundNo() + 1;
                ReworkRound next = new ReworkRound();
                next.setReworkId(order.getId());
                next.setRoundNo(nextNo);
                next.setSourceInspectId(savedInspection.getId());
                next.setRoundStatus("PENDING");
                next.setCreatedAt(now);
                roundRepository.save(next);

                order.setCurrentRound(nextNo);
                order.setStage("REWORKING");
                orderRepository.save(order);
                casting.setStatus("REWORK");
                castingRepository.save(casting);

                recordEvent(order.getId(), round.getRoundNo(), "REINSPECTED", savedInspection.getInspector(),
                        "复检仍需返修（复检单 " + savedInspection.getInspectNo() + "，缺陷 " + defectCount
                                + " 条），第 " + nextNo + " 轮待办已生成", now);
                result.put("closed", null);
                result.put("nextRound", next);
            }
            default -> throw new BizException("复检结论非法：" + request.getVerdict());
        }
        return result;
    }

    // ================= 追加更正说明（复检创建后唯一的修正方式） =================

    @Transactional
    public ReworkCorrection correct(Long reworkId, CorrectionRequest request) {
        ReworkOrder order = orderRepository.lockById(reworkId)
                .orElseThrow(() -> new BizException("返修单不存在：" + reworkId));
        String target = request.getTarget();
        if (!"REWORK".equals(target) && !"REINSPECT".equals(target)) {
            throw new BizException("更正对象只能是 REWORK（返修处置）或 REINSPECT（复检结论）");
        }
        if (!StringUtils.hasText(request.getContent())) {
            throw new BizException("更正说明内容不能为空");
        }

        // 找到最近一轮已创建复检单的轮次——被冻结、允许追加更正的就是它；
        // 正在返修中的新轮处置还没冻结，应直接撤回重填而不是写更正。
        ReworkRound frozen = null;
        for (ReworkRound r : roundRepository.findByReworkIdOrderByRoundNoAsc(order.getId())) {
            if (r.getReinspectId() != null) {
                frozen = r;
            }
        }
        if (frozen == null) {
            throw new BizException("当前没有已进入复检的轮次：返修完成结果在复检创建前可直接撤回重填，无需追加更正");
        }

        LocalDateTime now = LocalDateTime.now();
        ReworkCorrection correction = new ReworkCorrection();
        correction.setReworkId(order.getId());
        correction.setRoundId(frozen.getId());
        correction.setTargetType(target);
        correction.setContent(request.getContent().trim());
        correction.setOperator(StringUtils.hasText(request.getOperator()) ? request.getOperator().trim() : null);
        correction.setCreatedAt(now);
        ReworkCorrection saved = correctionRepository.save(correction);

        recordEvent(order.getId(), frozen.getRoundNo(), "CORRECTED", correction.getOperator(),
                "追加「" + Dict.correctionTargetName(target) + "」更正：" + correction.getContent()
                        + "（原始记录保留不变）", now);
        return saved;
    }

    // ================= 证据保护：检验单是否被返修链路引用 =================

    /**
     * 检查一张检验单是否作为缺陷快照 / 复检结论被返修链路引用。
     * 返回非空的引用描述时禁止删除检验单；未被引用返回 null。
     */
    @Transactional(readOnly = true)
    public String describeSnapshotReference(Long inspectId) {
        ReworkOrder asSnapshot = orderRepository.findBySnapshotInspectId(inspectId).orElse(null);
        if (asSnapshot != null) {
            return "首次缺陷快照（返修单 " + asSnapshot.getReworkNo() + "）";
        }
        ReworkRound asSource = roundRepository.findFirstBySourceInspectId(inspectId).orElse(null);
        if (asSource != null) {
            return "第 " + asSource.getRoundNo() + " 轮缺陷快照（返修单 " + orderNo(asSource.getReworkId()) + "）";
        }
        ReworkRound asReinspect = roundRepository.findFirstByReinspectId(inspectId).orElse(null);
        if (asReinspect != null) {
            return "第 " + asReinspect.getRoundNo() + " 轮复检结论（返修单 " + orderNo(asReinspect.getReworkId()) + "）";
        }
        return null;
    }

    private String orderNo(Long orderId) {
        return orderRepository.findById(orderId).map(ReworkOrder::getReworkNo).orElse("#" + orderId);
    }

    // ================= 内部工具 =================

    /** 必须在锁完返修单后调用，保持 返修单 → 轮次 的固定加锁顺序 */
    private ReworkRound lockCurrentRound(ReworkOrder order) {
        Long id = roundRepository.findByReworkIdAndRoundNo(order.getId(), order.getCurrentRound())
                .map(ReworkRound::getId)
                .orElseThrow(() -> new BizException("返修单 " + order.getReworkNo() + " 的第 "
                        + order.getCurrentRound() + " 轮数据缺失"));
        return roundRepository.lockById(id)
                .orElseThrow(() -> new BizException("返修轮次不存在：" + id));
    }

    private Casting lockCasting(ReworkOrder order) {
        // 先纯表行锁（不做实体映射，避开 @SQLSelect 的 join 投影），再按实体加载器取铸件
        Long lockedId = castingRepository.lockRowById(order.getCastingId());
        if (lockedId == null) {
            throw new BizException("铸件卡不存在：" + order.getCastingId());
        }
        return castingRepository.findById(order.getCastingId())
                .orElseThrow(() -> new BizException("铸件卡不存在：" + order.getCastingId()));
    }

    private void recordEvent(Long reworkId, Integer roundNo, String type, String operator, String detail,
                             LocalDateTime when) {
        ReworkEvent event = new ReworkEvent();
        event.setReworkId(reworkId);
        event.setRoundNo(roundNo);
        event.setEventType(type);
        event.setOperator(operator);
        event.setDetail(detail);
        event.setCreatedAt(when);
        eventRepository.save(event);
    }

    private static String nullToDash(String s) {
        return StringUtils.hasText(s) ? s : "未署名";
    }

    private static String fmt(LocalDateTime time) {
        return time == null ? "—" : time.format(TIP_TIME);
    }
}
