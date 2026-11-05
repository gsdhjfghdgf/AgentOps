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
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> malformedBody(RuntimeException ex){
        Throwable cause=ex;while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();
        return cause instanceof RequestLimitsFilter.TooLarge?error(HttpStatus.PAYLOAD_TOO_LARGE,"请求内容过大，请通过文件导入资料"):error(HttpStatus.BAD_REQUEST,"请求内容为空或格式不正确，请检查输入");
    }
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            jakarta.validation.ConstraintViolationException.class})
    public ResponseEntity<Map<String,Object>> invalidRequest(Exception ex){return error(HttpStatus.BAD_REQUEST,"请求参数缺失、格式或长度不正确，请检查输入");}
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> status(org.springframework.web.server.ResponseStatusException ex){
        return error(HttpStatus.valueOf(ex.getStatusCode().value()),ex.getReason());
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String,Object>> integrity(RuntimeException ex){return error(HttpStatus.CONFLICT,"数据已存在或状态已变化，请刷新后重试");}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String,Object>> database(RuntimeException ex){return error(HttpStatus.SERVICE_UNAVAILABLE,"数据服务暂不可用，请稍后重试");}
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String,Object>> method(Exception ex){return error(HttpStatus.METHOD_NOT_ALLOWED,"此接口不支持当前操作");}
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String,Object>> media(Exception ex){return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"请求内容类型不受支持");}
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<Map<String,Object>> noResource(Exception ex){return error(HttpStatus.NOT_FOUND,"接口不存在");}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> unexpected(Exception ex,jakarta.servlet.http.HttpServletResponse response){
        if(response.isCommitted())return null;
        String requestId=java.util.UUID.randomUUID().toString();
        // Keep credentials, SQL arguments and request bodies out of public errors and routine logs.
        log.error("接口异常 reference={} type={} at={}",requestId,ex.getClass().getName(),ex.getStackTrace().length>0?ex.getStackTrace()[0]:"unknown");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).header("X-Request-Id",requestId)
                .body(Map.of("error","INTERNAL_SERVER_ERROR","message","服务暂时出现问题，请重试；仍失败时请提供错误编号","requestId",requestId));
    }
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
        return ResponseEntity.status(status).body(Map.of("error", status.name(), "message", message==null||message.isBlank()?"请求处理失败，请稍后重试":message));
    }
}
