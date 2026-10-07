/**
 * 进程内判定门面。
 *
 * <p>把内核包成一个库：调用方拿到 {@code check} / {@code checkAll} / {@code lookup} / {@code whoCan}
 * 四个方法，不必自己管理 {@code Ctx} 或装求值器。本模块只依赖内核，不引入任何其它依赖——
 * 与 {@code facet-core} 零三方依赖的护城河保持一致。
 */
module facet.sdk {
    requires facet.core;

    exports facet.sdk;
}
