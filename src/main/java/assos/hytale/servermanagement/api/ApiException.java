package assos.hytale.servermanagement.api;

/**
 * Exception carrying the HTTP status and machine-readable code returned by the
 * REST API.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "bad_request", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, "unauthorized", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "not_found", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "conflict", message);
    }

    public static ApiException methodNotAllowed(String message) {
        return new ApiException(405, "method_not_allowed", message);
    }

    public static ApiException internal(String message) {
        return new ApiException(500, "internal_error", message);
    }
}
