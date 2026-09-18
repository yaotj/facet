package facet.core.eval;

import facet.core.ir.AttrKey;
import facet.core.ir.Cond;
import facet.core.ir.ObjectRef;
import facet.core.ir.Rel;

import java.util.List;
import java.util.SequencedCollection;

/**
 * 判定树。
 *
 * <p>用 {@link SequencedCollection} 而不是 {@code Collection}：顺序确定 → 序列化出的
 * 文本稳定 → 判定矩阵才能做 golden file 快照测试。顺序不确定的 explain 做不了回归。
 */
public sealed interface Explain {

    /** 命中：在 {@code via} 上找到 {@code rel} 元组。 */
    record Hit(Rel rel, ObjectRef via) implements Explain {}

    /** 未命中：{@code at} 上没有任何满足 {@code rel} 的路径。 */
    record Miss(Rel rel, ObjectRef at) implements Explain {}

    record Branch(String op, SequencedCollection<Explain> children) implements Explain {
        public Branch {
            children = List.copyOf(children);
        }
    }

    record CondEval(Cond cond, boolean result, AttrKey.Tier tier) implements Explain {}

    /** 被 {@code Minus} 排除。单调，上层不可恢复。 */
    record DeniedBy(Explain cause) implements Explain {}

    /** 环剪枝：该 (perm, obj) 已在当前路径上。 */
    record CycleCut(ObjectRef at) implements Explain {}

    record DepthExceeded(int limit) implements Explain {}

    /**
     * 子树里是否发生过剪枝。
     *
     * <p>只在记忆化时用：剪枝结果依赖<strong>当前路径</strong>，缓存了就会污染别的路径。
     */
    static boolean hasCut(Explain explain) {
        return switch (explain) {
            case Hit _, Miss _, CondEval _ -> false;
            case CycleCut _, DepthExceeded _ -> true;
            case DeniedBy(var cause) -> hasCut(cause);
            case Branch(_, var children) -> children.stream().anyMatch(Explain::hasCut);
        };
    }
}
