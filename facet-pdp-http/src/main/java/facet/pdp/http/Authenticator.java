package facet.pdp.http;

/**
 * 请求鉴权。
 *
 * <p>没有默认实现，也没有"允许全部"的常量：PDP 的答案就是整套授权系统的答案，
 * 一个不鉴权的 PDP 等于把它免费送出去。做成必填参数，就没有"忘了配"这种事故。
 *
 * <p>带 {@link Scope} 是因为读与写的影响面差了一个量级：能读的客户端拿到的是判定结果，
 * 能写的客户端可以直接改写授权数据本身。共用一份凭据意味着任何一个只需要 check 的服务
 * 都顺带获得了改写全局权限的能力。
 *
 * <p>实现应当用<strong>定长时间</strong>比较凭据（{@code MessageDigest.isEqual}），
 * 而不是 {@code String.equals}——后者会在第一个不同字节处返回，可被计时攻击逐字节猜出。
 */
@FunctionalInterface
public interface Authenticator {

    /** 端点所需的能力。 */
    enum Scope {

        /** check 与 lookup-resources。 */
        READ,

        /** 关系写入。影响面是改写授权数据本身。 */
        WRITE
    }

    /**
     * @param authorization {@code Authorization} 请求头的原始值，缺失时为 {@code null}
     * @param scope         本次请求所需能力
     * @return 是否放行
     */
    boolean allows(String authorization, Scope scope);
}
