package com.casting.shop.repository;

import com.casting.shop.domain.ReworkOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReworkOrderRepository extends JpaRepository<ReworkOrder, Long> {

    boolean existsByReworkNo(String reworkNo);

    boolean existsByCastingId(Long castingId);

    Optional<ReworkOrder> findByCastingId(Long castingId);

    Optional<ReworkOrder> findBySnapshotInspectId(Long snapshotInspectId);

    List<ReworkOrder> findAllByOrderByIdDesc();

    List<ReworkOrder> findByStageOrderByIdDesc(String stage);

    long countByStage(String stage);

    /**
     * 行锁加载返修单。返修流程里所有会改轮次/状态的动作都先锁返修单行，
     * 再锁轮次行（统一加锁顺序：返修单 → 轮次），把两名现场人员的并发提交
     * 在数据库层串行化，同一轮只有一次提交能成功。
     */
    @Query(value = "select * from rework_order where id = :id for update", nativeQuery = true)
    Optional<ReworkOrder> lockById(Long id);
}
