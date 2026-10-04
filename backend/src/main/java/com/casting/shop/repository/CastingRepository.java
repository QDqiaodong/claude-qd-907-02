package com.casting.shop.repository;

import com.casting.shop.domain.Casting;
import com.casting.shop.dto.CastingStatusDTO;
import com.casting.shop.dto.MaterialStatDTO;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 铸件卡仓储。
 *
 * <p>实体 {@link Casting} 上标注了 {@code @SQLSelect}，因此 {@code findById} 走的是那条
 * left join heat / mold 的自定义加载 SQL；这里额外给列表也写了一条同口径的原生查询，
 * 让列表页同样拿得到 join 出来的只读派生字段（heat_no / mold_no）。</p>
 */
public interface CastingRepository extends JpaRepository<Casting, Long> {

    boolean existsByCastingNo(String castingNo);

    List<Casting> findByHeatIdOrderByIdAsc(Long heatId);

    List<Casting> findByMoldIdOrderByIdAsc(Long moldId);

    long countByStatus(String status);

    /** 手写 JPQL ②：按状态统计铸件数量与理论重量合计 */
    @Query("select new com.casting.shop.dto.CastingStatusDTO("
            + "c.status, count(c), coalesce(sum(c.theoryWeight), 0)) "
            + "from Casting c group by c.status order by count(c) desc")
    List<CastingStatusDTO> statByStatus();

    /** 手写 JPQL ③：按材质统计铸件数量与理论重量合计 */
    @Query("select new com.casting.shop.dto.MaterialStatDTO("
            + "c.material, count(c), coalesce(sum(c.theoryWeight), 0)) "
            + "from Casting c group by c.material order by count(c) desc")
    List<MaterialStatDTO> statByMaterial();

    /**
     * 列表也走同一条 join SQL（与 @SQLSelect 口径一致），这样页面上每张卡片都能直接读到
     * 派生出来的炉次号 / 炉次材质 / 模具编号 / 模具名称。
     */
    @Query(value = "select c.*, "
            + "h.heat_no as heat_no, h.alloy as heat_alloy, "
            + "m.mold_no as mold_no, m.mold_name as mold_name "
            + "from casting c "
            + "left join heat h on h.id = c.heat_id "
            + "left join mold m on m.id = c.mold_id "
            + "order by c.id desc", nativeQuery = true)
    List<Casting> listDerived();

    /**
     * 仅为铸件行加锁，不做实体映射：实体 Casting 上有 @SQLSelect 的 join 投影
     * （heat_no / heat_alloy / mold_no / mold_name），把「纯表 FOR UPDATE」的窄结果集
     * 直接映射成实体会缺列。业务流程先调本方法拿行锁，再用 findById 取实体。
     * 首次检验开单 / 返修闭环状态回写 / 手工状态流转都先锁铸件行，
     * 挡住现场重复点击造成的双开单、双流转。
     */
    @Query(value = "select id from casting where id = :id for update", nativeQuery = true)
    Long lockRowById(@Param("id") Long id);

    /** 只读状态的原生标量查询，不经过实体上的 @SQLSelect 自定义加载器 */
    @Query(value = "select status from casting where id = :id", nativeQuery = true)
    String findStatusById(@Param("id") Long id);
}
