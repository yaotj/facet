package facet.pdp.http;

/** 请求体超过服务端硬上限。 */
final class BodyTooLarge extends RuntimeException {

    BodyTooLarge(String message) {
        super(message);
    }
}
