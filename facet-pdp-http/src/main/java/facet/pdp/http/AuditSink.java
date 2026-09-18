package facet.pdp.http;

import facet.core.ir.ObjectRef;
import facet.core.ir.Rel;
import facet.core.ir.Revision;
import facet.core.ir.SubjectRef;

/**
 * 审计日志。
 *
 * <p>与 {@code Metrics} 分开：指标是聚合数值，审计是逐条事件。授权系统通常要求判定可追溯
 * ——"谁在什么时候被拒了"这个问题在事故复盘和合规审查里都会被问到，而聚合指标答不出来。
 *
 * <p>刻意不带 explain：判定树会暴露完整关系图，逐条落盘既昂贵又是一个敞开的信息面。
 * 需要路径信息时用 {@code explain=true} 单独查那一次判定。
 *
 * <p>默认 {@link #NONE}。审计要落到哪里、保留多久、是否脱敏，全是部署决策。
 */
@FunctionalInterface
public interface AuditSink {

    /**
     * 一次判定已完成。
     *
     * @param at 判定所依据的一致性坐标；{@code HEAD} 表示读的是最新
     */
    void decided(SubjectRef subject, ObjectRef object, Rel relation, boolean allowed, Revision at);

    /** 不审计。 */
    AuditSink NONE = (subject, object, relation, allowed, at) -> {
    };
}
