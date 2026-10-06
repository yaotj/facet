package facet.core.runtime;

import facet.core.ir.ObjectRef;

/**
 * 判定树的稳定文本形式。
 *
 * <p>放在内核而不是 testkit：它是 {@code Explain} 的<strong>契约化序列化</strong>——
 * golden file 快照依赖它，PDP 的调试响应也依赖它。两处各写一份渲染器，就会出现
 * "测试基线里对得上、线上排查时对不上"。
 *
 * <p>{@code Explain.Branch} 用 {@code SequencedCollection} 的意义在这里兑现：顺序确定 →
 * 这段文本确定 → diff 才有意义。
 */
public final class Explains {

    private Explains() {
    }

    /**
     * 渲染成缩进文本。缩进代表父子关系，同级顺序即求值顺序，所以两次判定的 diff 可以逐行比。
     *
     * <p>格式本身是契约：改了它就等于让所有 golden file 基线失效，必须连基线一起更新。
     */
    public static String render(Explain explain) {
        var out = new StringBuilder();
        render(explain, 0, out);
        return out.toString();
    }

    /** 没有 {@code default} 分支：{@code Explain} 加节点类型，这里立刻编译失败。 */
    private static void render(Explain explain, int depth, StringBuilder out) {
        var pad = "  ".repeat(depth);
        switch (explain) {
            case Explain.Hit(var rel, var via) ->
                    out.append(pad).append("HIT ").append(ref(via)).append('#').append(rel.name()).append('\n');
            case Explain.Miss(var rel, var at) ->
                    out.append(pad).append("MISS ").append(ref(at)).append('#').append(rel.name()).append('\n');
            case Explain.CondEval(var cond, var result, var tier) ->
                    out.append(pad).append("COND ").append(result).append(" [").append(tier)
                            .append("] ").append(cond).append('\n');
            case Explain.CycleCut(var at) ->
                    out.append(pad).append("CYCLE-CUT ").append(ref(at)).append('\n');
            case Explain.DepthExceeded(var limit) ->
                    out.append(pad).append("DEPTH-EXCEEDED ").append(limit).append('\n');
            case Explain.DeniedBy(var cause) -> {
                out.append(pad).append("DENIED-BY\n");
                render(cause, depth + 1, out);
            }
            case Explain.Branch(var op, var children) -> {
                out.append(pad).append(op).append('\n');
                children.forEach(child -> render(child, depth + 1, out));
            }
        }
    }

    private static String ref(ObjectRef obj) {
        return obj.type().name() + ':' + obj.id();
    }
}
