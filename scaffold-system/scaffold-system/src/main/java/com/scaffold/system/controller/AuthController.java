package com.scaffold.system.controller;

import com.scaffold.common.domain.R;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.framework.audit.OperLog;
import com.scaffold.framework.security.CaptchaService;
import com.scaffold.framework.security.LoginChallengeService;
import com.scaffold.system.domain.dto.ChangePasswordRequest;
import com.scaffold.system.domain.dto.LoginRequest;
import com.scaffold.system.domain.vo.CurrentUserResponse;
import com.scaffold.system.domain.vo.LoginResponse;
import com.scaffold.system.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final LoginChallengeService loginChallenge;

    public AuthController(AuthService authService, CaptchaService captchaService, LoginChallengeService loginChallenge) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.loginChallenge = loginChallenge;
    }

    @GetMapping("/captcha")
    public R<Map<String, String>> captcha() {
        return R.success(captchaService.generate());
    }

    /** 登录前查询该账号是否需要验证码：首次登录为 false，失败后窗口期内为 true */
    @GetMapping("/login-challenge")
    public R<Map<String, Object>> challenge(@RequestParam(required = false) String username) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("captchaRequired", loginChallenge.isRequired(username));
        result.put("windowSeconds", loginChallenge.windowSeconds());
        return R.success(result);
    }

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        String username = request.getUsername().trim();
        // 只有上次登录失败后的窗口期内才校验验证码；其余情况忽略验证码字段，首次登录直接放行
        if (loginChallenge.isRequired(username)) {
            String captcha = request.getCaptcha();
            if (captcha == null || captcha.isBlank()) {
                throw new BusinessException("请输入验证码");
            }
            if (!captchaService.verify(request.getCaptchaUuid(), captcha)) {
                throw new BusinessException("验证码错误或已过期");
            }
        }
        try {
            LoginResponse response = authService.login(request);
            loginChallenge.clear(username);
            return R.success(response);
        } catch (BusinessException exception) {
            // 账号密码错误、账号停用等：开启（或顺延）验证码窗口
            loginChallenge.markFailure(username);
            throw exception;
        }
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.success(null);
    }

    @GetMapping("/me")
    public R<CurrentUserResponse> me() {
        return R.success(authService.currentUser());
    }

    @GetMapping("/routes")
    public R<Map<String, Object>> routes() {
        return R.success(authService.routes());
    }

    @PutMapping("/password")
    @OperLog(module = "账号安全", action = "修改密码")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return R.success(null);
    }
}
