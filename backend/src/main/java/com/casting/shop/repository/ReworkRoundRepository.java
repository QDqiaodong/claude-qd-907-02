package com.casting.shop.repository;

import com.casting.shop.domain.ReworkRound;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReworkRoundRepository extends JpaRepository<ReworkRound, Long> {

    List<ReworkRound> findByReworkIdOrderByRoundNoAsc(Long reworkId);

    Optional<ReworkRound> findByReworkIdAndRoundNo(Long reworkId, Integer roundNo);

    /** 该检验单是否作为某轮缺陷快照来源 */
    boolean existsBySourceInspectId(Long sourceInspectId);

    /** 该检验单是否作为某轮复检结论单 */
    boolean existsByReinspectId(Long reinspectId);

    /** 找一张检验单对应的快照轮次（用于删除检验单时的拦截提示） */
    Optional<ReworkRound> findFirstBySourceInspectId(Long sourceInspectId);

    Optional<ReworkRound> findFirstByReinspectId(Long reinspectId);

    /** 行锁加载轮次行（调用方必须先锁返修单，保持 返修单 → 轮次 的加锁顺序） */
    @Query(value = "select * from rework_round where id = :id for update", nativeQuery = true)
    Optional<ReworkRound> lockById(Long id);
}
