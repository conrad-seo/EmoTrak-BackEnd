package com.example.emotrak.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class CustomExceptionHandler {
    @ExceptionHandler(value = { RuntimeException.class })
    protected ResponseEntity handleCustomException(CustomException e) {
        return ResponseMessage.errorResponse(e.getErrorCode());
    }
}

