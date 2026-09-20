package com.eduplatform.eduplatform_backend.common.error;

import com.eduplatform.eduplatform_backend.common.web.ApiError;
import com.eduplatform.eduplatform_backend.common.web.FieldErrorItem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
// MaxUploadSizeExceededException extends MultipartException; Spring dispatches to the most
// specific @ExceptionHandler, so the size-specific message wins over the generic one.
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Single source of truth for HTTP error responses.
 * Returns {@link ApiError} bodies with stable machine-readable {@code code}s.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiError> handleApp(AppException ex, HttpServletRequest req) {
        return ResponseEntity.status(ex.status())
                .body(ApiError.of(ex.status().value(), ex.code(), ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<FieldErrorItem> items = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toItem)
                .toList();
        return ResponseEntity.badRequest().body(ApiError.validation(req.getRequestURI(), items));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        List<FieldErrorItem> items = ex.getConstraintViolations().stream()
                .map(v -> new FieldErrorItem(v.getPropertyPath().toString(), "INVALID", v.getMessage(), v.getInvalidValue()))
                .toList();
        return ResponseEntity.badRequest().body(ApiError.validation(req.getRequestURI(), items));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(
                ApiError.of(400, "MALFORMED_JSON", "Request body is malformed or missing", req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(
                ApiError.of(400, "INVALID_PARAMETER",
                        "Parameter '" + ex.getName() + "' has an invalid value", req.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                ApiError.of(405, "METHOD_NOT_ALLOWED", ex.getMessage(), req.getRequestURI()));
    }

    // The four handlers below cover request-shape errors that Spring raises before any
    // controller code runs. Because this advice also handles Exception, Spring's own resolver
    // never sees them, so without explicit handlers each one became a 500 plus an ERROR stack
    // trace that any anonymous caller could trigger at will: GET /api/public/courses/search
    // without q, or POST /api/auth/login sent as text/plain. They are the client's mistake, so
    // they are not logged at all.

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex,
                                                           HttpServletRequest req) {
        return missingParameter("parameter '" + ex.getParameterName() + "'", req);
    }

    /** A multipart request without a part that a {@code @RequestPart} or required {@code MultipartFile} names. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest req) {
        return missingParameter("part '" + ex.getRequestPartName() + "'", req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                               HttpServletRequest req) {
        // getContentType() is null when the header could not be parsed at all.
        String sent = ex.getContentType() == null
                ? "The Content-Type header is malformed"
                : "Content type '" + ex.getContentType() + "' is not supported here";
        String supported = ex.getSupportedMediaTypes().isEmpty()
                ? ""
                : "; send " + MediaType.toString(ex.getSupportedMediaTypes());
        // ex.getHeaders() carries Accept (and Accept-Patch for PATCH) listing what the endpoint
        // does take, the same header Spring's default resolver would have sent with a 415.
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(ex.getHeaders())
                .body(ApiError.of(415, "UNSUPPORTED_MEDIA_TYPE", sent + supported, req.getRequestURI()));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex,
                                                        HttpServletRequest req) {
        // The Content-Type is fixed rather than negotiated. This client has just said it does not
        // accept JSON, so negotiating this body would fail the same way, and Spring would then
        // discard the handler's response and fall back to a bare sendError(406). RFC 9110 allows
        // a 406 to carry a representation the client did not ask for.
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(406, "NOT_ACCEPTABLE",
                        "This endpoint cannot respond in any media type listed in the Accept header",
                        req.getRequestURI()));
    }

    /**
     * {@code ?sort=} names an entity property that Spring Data resolves only when the query runs,
     * so on a {@code Pageable} endpoint with no sort whitelist an unknown name surfaces from the
     * repository. It is the caller's mistake, repeatable anonymously at will, so it gets the 400
     * the catalogue's own sort check already returns rather than a 500 and an ERROR stack trace.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiError> handleUnknownProperty(PropertyReferenceException ex, HttpServletRequest req) {
        return invalidSortProperty(ex, req);
    }

    /**
     * Persistence exception translation can hand the same unknown sort property back wrapped in
     * this type. Only that wrapped form is the client's error; any other misuse of the
     * data-access API is a defect in our own code and keeps the 500 and the ERROR log.
     */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ApiError> handleDataAccessMisuse(InvalidDataAccessApiUsageException ex,
                                                           HttpServletRequest req) {
        PropertyReferenceException unknownProperty = propertyReferenceCause(ex);
        return unknownProperty != null ? invalidSortProperty(unknownProperty, req) : handleAny(ex, req);
    }

    @ExceptionHandler({NoSuchElementException.class})
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(404, "NOT_FOUND", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        // PG exclusion constraint, unique constraint, FK violation, etc.
        log.debug("Data integrity violation", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(409, "CONSTRAINT_VIOLATION", "Operation conflicts with existing data", req.getRequestURI()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimistic(OptimisticLockingFailureException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(409, "STALE_RESOURCE", "Resource was modified concurrently; please retry", req.getRequestURI()));
    }

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ApiError> handleAuth(Exception ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiError.of(401, "AUTHENTICATION_FAILED", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(AccessDeniedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiError.of(403, "FORBIDDEN", "You do not have permission to access this resource", req.getRequestURI()));
    }

    /**
     * A request for a path that maps to nothing is a 404, not a server error. Without this
     * it falls through to {@link #handleAny} and every scanner or stale link produces an
     * ERROR log line and a 5xx datapoint, burying real failures.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(404, "NOT_FOUND", "Resource not found", req.getRequestURI()));
    }

    /**
     * A multipart body larger than {@code spring.servlet.multipart.max-file-size} is rejected
     * by the servlet container before any controller runs, so the per-kind checks in
     * {@code MediaService} never get a chance to produce their own message. Without this
     * handler it reaches {@link #handleAny} and an ordinary too-big file becomes a 500 plus an
     * ERROR log line — indistinguishable from a real fault, and the uploader is told nothing
     * it can act on.
     *
     * <p>413 rather than 400: the request was well formed, it was simply too large. The
     * message deliberately does not name the servlet ceiling, because it is not the limit
     * that usually applies — {@code app.uploads.max-image-mb} / {@code max-video-mb} /
     * {@code max-document-mb} are lower and are what the client is really bound by.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        log.debug("Rejected an upload that exceeded the servlet multipart limit", ex);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                ApiError.of(413, "UPLOAD_TOO_LARGE",
                        "The uploaded file is larger than this endpoint accepts", req.getRequestURI()));
    }

    /**
     * Anything else malformed about a multipart request — a truncated body from a dropped
     * connection mid-upload is the common one. Also a 4xx, for the same reason as above: it
     * is the client's request that was broken, and letting it log at ERROR buries real
     * failures behind routine flaky uploads.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> handleMultipart(MultipartException ex, HttpServletRequest req) {
        log.debug("Malformed multipart request", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.of(400, "MALFORMED_UPLOAD",
                        "The upload request was malformed or ended early", req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAny(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiError.of(500, "INTERNAL_ERROR", "An unexpected error occurred", req.getRequestURI()));
    }

    private static ResponseEntity<ApiError> invalidSortProperty(PropertyReferenceException ex,
                                                                HttpServletRequest req) {
        // DEBUG, not ERROR: a Sort built in our own code with a bad name lands here too, and this
        // line is what tells that bug apart from a client typo.
        log.debug("Rejected an unknown sort property", ex);
        // Only the property name is echoed: the exception's own message names the entity class.
        return ResponseEntity.badRequest().body(
                ApiError.of(400, "INVALID_SORT_PROPERTY",
                        "Results cannot be sorted by '" + ex.getPropertyName() + "'", req.getRequestURI()));
    }

    private static PropertyReferenceException propertyReferenceCause(Throwable ex) {
        // Identity-tracked because nothing stops a cause chain from looping back on itself.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = ex.getCause(); t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof PropertyReferenceException pre) return pre;
        }
        return null;
    }

    private static ResponseEntity<ApiError> missingParameter(String what, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(
                ApiError.of(400, "MISSING_PARAMETER", "Required " + what + " is missing", req.getRequestURI()));
    }

    private static FieldErrorItem toItem(FieldError fe) {
        return new FieldErrorItem(fe.getField(),
                fe.getCode() == null ? "INVALID" : fe.getCode().toUpperCase(),
                fe.getDefaultMessage(), fe.getRejectedValue());
    }
}
