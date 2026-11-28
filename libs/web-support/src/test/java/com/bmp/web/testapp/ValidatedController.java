package com.bmp.web.testapp;

import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Parameters validated through a {@code @Validated} proxy, as the generated API interfaces are. */
@RestController
@Validated
@RequestMapping("/test/validated")
public class ValidatedController {

    @GetMapping
    @PreAuthorize("permitAll()")
    public ResponseEntity<String> period(@RequestParam @Pattern(regexp = "^[0-9]{4}-(0[1-9]|1[0-2])$") String period) {
        return ResponseEntity.ok(period);
    }
}
