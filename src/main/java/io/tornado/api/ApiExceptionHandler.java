package io.tornado.api;
import org.springframework.dao.DataIntegrityViolationException; import org.springframework.http.*; import org.springframework.web.bind.MethodArgumentNotValidException; import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice public class ApiExceptionHandler {
 @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class}) ResponseEntity<Map<String,String>> bad(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<Map<String,String>> conflict(Exception e){return ResponseEntity.status(409).body(Map.of("error","Coin symbol or pair already exists"));}
}
