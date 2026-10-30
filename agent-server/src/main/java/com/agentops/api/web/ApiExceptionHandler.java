package com.agentops.api.web;

import com.agentops.api.RunApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** Shared error mapping for auxiliary controllers as well as the main API. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> invalidArgument(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST,ex.getMessage()==null?"参数无效":ex.getMessage());
    }
    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<Map<String,Object>> ioError(java.io.IOException ex, jakarta.servlet.http.HttpServletResponse response) {
        // Closing an SSE tab or pausing a stream is expected; JSON cannot be written into a committed event stream.
        if(response.isCommitted() || (response.getContentType()!=null && response.getContentType().startsWith("text/event-stream")))
            return null;
        return error(HttpStatus.BAD_GATEWAY,"读取文件或下载失败，请检查路径、网络和源地址。"+(ex.getMessage()!=null && ex.getMessage().startsWith("网页返回 HTTP")?ex.getMessage():""));
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String,Object>> uploadTooLarge(RuntimeException ex) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE,"文件不能超过 20 MB");
    }
    @ExceptionHandler(ApiController.ApiException.class)
    public ResponseEntity<Map<String, Object>> apiError(ApiController.ApiException ex) {
        return error(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(RunApplicationService.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(RuntimeException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(RunApplicationService.ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(RuntimeException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RunApplicationService.BadRequestException.class)
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", status.name(), "message", message));
    }
}
