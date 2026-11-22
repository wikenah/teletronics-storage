package com.teletronics.storage.presentation.http.problem;

import com.teletronics.storage.application.FileTooLargeException;
import com.teletronics.storage.application.IncompleteUploadException;
import com.teletronics.storage.application.UploadCapacityReachedException;
import com.teletronics.storage.application.port.StorageUnavailableException;
import com.teletronics.storage.domain.file.DuplicateFileException;
import com.teletronics.storage.domain.file.FileNotFoundException;
import com.teletronics.storage.domain.file.InvalidFileException;
import com.teletronics.storage.domain.file.RepositoryUnavailableException;
import com.teletronics.storage.domain.file.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every failure into RFC 9457 Problem Details (docs/acceptance-criteria/http-api.md, decision 6).
 * A 500 never exposes internal details.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(InvalidFileException.class)
    ResponseEntity<ProblemDetail> invalidFile(InvalidFileException e) {
        return invalid(e.field(), e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ProblemDetail> invalidRequest(InvalidRequestException e) {
        return invalid(e.field(), e.getMessage());
    }

    @ExceptionHandler(IncompleteUploadException.class)
    ResponseEntity<ProblemDetail> incomplete(IncompleteUploadException e) {
        return invalid("body", e.getMessage());
    }

    @ExceptionHandler(DuplicateFileException.class)
    ResponseEntity<ProblemDetail> duplicate(DuplicateFileException e) {
        boolean byName = e.kind() == DuplicateFileException.Kind.FILENAME;
        ProblemDetail problem = problem(HttpStatus.CONFLICT,
                byName ? ProblemTypes.FILENAME_EXISTS : ProblemTypes.CONTENT_EXISTS,
                byName ? "Filename already exists" : "Content already exists",
                byName ? "You already have a file with this name" : "You already have a file with this content");
        problem.setProperty("existingFile", existingFile(e.existing()));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(FileNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(FileNotFoundException e) {
        return respond(problem(HttpStatus.NOT_FOUND, ProblemTypes.FILE_NOT_FOUND, "File not found", null));
    }

    @ExceptionHandler(UploadCapacityReachedException.class)
    ResponseEntity<ProblemDetail> capacity(UploadCapacityReachedException e) {
        return respond(problem(HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.UPLOAD_CAPACITY_REACHED,
                "Upload capacity reached", "Too many uploads are running; try again later"));
    }

    @ExceptionHandler(FileTooLargeException.class)
    ResponseEntity<ProblemDetail> tooLarge(FileTooLargeException e) {
        return respond(problem(HttpStatus.CONTENT_TOO_LARGE, ProblemTypes.FILE_TOO_LARGE, "File too large", e.getMessage()));
    }

    @ExceptionHandler({StorageUnavailableException.class, RepositoryUnavailableException.class})
    ResponseEntity<ProblemDetail> unavailable(RuntimeException e) {
        log.warn("dependency unavailable: {}", e.getMessage());
        return respond(problem(HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.TEMPORARILY_UNAVAILABLE,
                "Temporarily unavailable", "A storage service is unavailable; try again later"));
    }

    @ExceptionHandler(FormBodyNotSupportedException.class)
    ResponseEntity<ProblemDetail> formBody(FormBodyNotSupportedException e) {
        return respond(ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage()));
    }

    @ExceptionHandler(UnsupportedContentEncodingException.class)
    ResponseEntity<ProblemDetail> encoding(UnsupportedContentEncodingException e) {
        ProblemDetail problem = problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ProblemTypes.UNSUPPORTED_CONTENT_ENCODING,
                "Unsupported content encoding", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .header(HttpHeaders.ACCEPT_ENCODING, "identity")
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("unexpected error", e);
        return respond(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_REQUEST,
                "Invalid request", "The request body is not valid JSON");
        return ResponseEntity.badRequest().body(problem);
    }

    private ResponseEntity<ProblemDetail> invalid(String field, String detail) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_REQUEST, "Invalid request", detail);
        if (field != null) {
            problem.setProperty("field", field);
        }
        return respond(problem);
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static Map<String, Object> existingFile(StoredFile file) {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("id", file.id().value());
        existing.put("size", file.size());
        existing.put("sha256", file.sha256().hex());
        return existing;
    }
}
